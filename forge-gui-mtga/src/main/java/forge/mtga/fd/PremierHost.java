package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import forge.item.PaperCard;
import forge.mtga.draft.DraftTable;
import forge.mtga.draft.TableDraft;
import forge.mtga.draft.TableLink;
import forge.mtga.profile.PublicProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * A premier table this server hosts for the people the coordinator seated with its player (slot 0). It waits for the
 * guests' hellos, seats everyone at random, runs the ready check (anyone not ready cancels the table), then the
 * {@link DraftTable}: the local player's seat is a {@link PremierDrafts.LocalTable}, every guest's a {@link RemoteSeat}
 * speaking {@link TableLink} frames. A guest who goes away is picked for like a bot until it is back.
 * <p>
 * Methods run on the link's thread (frames), the push executor (timers) or the Front Door's (the local player); state is
 * guarded by {@code this}.
 */
final class PremierHost implements TableLink.Listener {
    /** Longest wait for the guests' hellos before seating whoever is here. */
    private static final int HELLO_WAIT_MS = 10_000;
    /** Seconds a remote seat's pick timer runs longer than it is told (relay latency). */
    private static final int GRACE_SECONDS = 3;
    /** Longest gap between two pack-count broadcasts. */
    private static final int QUEUES_MS = 400;

    private enum State { FORMING, READY, DRAFTING, OVER, CANCELLED }

    private final PremierDrafts premier;
    private final PremierDrafts.Local local;
    private final TableLink.Seated seated;
    private final PremierDrafts.Online online;
    private volatile TableLink link;
    private State state = State.FORMING;
    /** Guests who said hello: slot -> profile (sanitized for this client). */
    private final Map<Integer, PublicProfile> hellos = new TreeMap<>();
    /** slot -> seat at the table (slot 0 = the local player). */
    private final Map<Integer, Integer> seatOf = new HashMap<>();
    private final Set<Integer> readySlots = new HashSet<>();
    private final Map<Integer, RemoteSeat> remotes = new HashMap<>();
    private List<PublicProfile> players = List.of();
    private DraftTable table;
    private boolean queuesPending;

    PremierHost(PremierDrafts premier, PremierDrafts.Local local, TableLink.Seated seated, PremierDrafts.Online online) {
        this.premier = premier;
        this.local = local;
        this.seated = seated;
        this.online = online;
    }

    /** Opens the table's room and gives the guests {@link #HELLO_WAIT_MS} to say hello. */
    void start() {
        premier.push.execute(() -> {
            try {
                link = TableLink.open(seated, this);
                link.start();
            } catch (IOException e) {
                System.out.println("[mtga] FD premier host: " + e.getMessage());
                cancel("the table could not be opened");
                return;
            }
            premier.push.schedule(this::seatAll, HELLO_WAIT_MS, TimeUnit.MILLISECONDS);
        });
    }

    // ---- the room -------------------------------------------------------------------------------------------------

    @Override
    public void control(JsonObject m) {
        String t = str(m, "t");
        int slot = m.has("slot") ? m.get("slot").getAsInt() : -1;
        if ("out".equals(t)) guestGone(slot);
        // "in": the guest sends its hello next
    }

    @Override
    public void closed() {
        synchronized (this) {
            if (state == State.OVER || state == State.CANCELLED) return;
        }
        System.out.println("[mtga] FD premier host: lost the table's room");
        synchronized (this) {
            if (state != State.DRAFTING) {
                premier.push.execute(() -> cancel("the table's connection was lost"));
                return;
            }
        }
        for (RemoteSeat r : remotes.values()) table.setAway(r.seat, true);
    }

    @Override
    public void frame(int type, int slot, JsonObject body) {
        switch (type) {
            case TableLink.HELLO -> hello(slot, body);
            case TableLink.READY -> ready(slot);
            case TableLink.PICK -> {
                RemoteSeat r = remote(slot);
                boolean ok = r != null && r.pick(body.has("grp") ? body.get("grp").getAsInt() : 0);
                send(TableLink.PICKED, slot, obj("n", body.has("n") ? body.get("n").getAsInt() : 0, "ok", ok));
            }
            case TableLink.RESERVE -> {
                RemoteSeat r = remote(slot);
                if (r != null) r.reserve(body.has("grp") ? body.get("grp").getAsInt() : 0);
            }
            case TableLink.BYE -> { /* the room tells "out" when the socket goes */ }
            default -> System.out.println("[mtga] FD premier host: unexpected frame " + type + " from slot " + slot);
        }
    }

    private synchronized RemoteSeat remote(int slot) { return remotes.get(slot); }

    private void hello(int slot, JsonObject h) {
        String problem = !TableLink.VERSION.equals(str(h, "v")) ? "another table version"
                : !online.fingerprint().equals(str(h, "fp")) ? "another card database"
                : !local.spec.product().equals(str(h, "spec")) ? "another " + (local.spec.isCube() ? "cube" : "set") : null;
        if (problem != null) {
            System.out.println("[mtga] FD premier host: slot " + slot + " refused: " + problem);
            send(TableLink.CLOSE, slot, obj("why", problem));
            return;
        }
        PublicProfile p = h.has("profile") && h.get("profile").isJsonObject() ? premier.profile.sanitize(PublicProfile.fromJson(h.getAsJsonObject("profile"))) : null;
        boolean seatNow = false;
        RemoteSeat back = null;
        synchronized (this) {
            switch (state) {
                case FORMING -> {
                    hellos.put(slot, p != null ? p : premier.profile.forAi("Player " + slot));
                    seatNow = hellos.size() >= seated.people() - 1;
                }
                case READY -> {
                    if (seatOf.containsKey(slot)) send(TableLink.SEATED, slot, seatedFrame(slot));
                    else send(TableLink.CLOSE, slot, obj("why", "the table is already full"));
                }
                case DRAFTING -> {
                    back = remotes.get(slot);
                    if (back == null) send(TableLink.CLOSE, slot, obj("why", "not at this table"));
                }
                default -> send(TableLink.CLOSE, slot, obj("why", "the table is over"));
            }
        }
        if (seatNow) seatAll();
        if (back != null) {
            System.out.println("[mtga] FD premier host: slot " + slot + " (seat " + back.seat + ") is back");
            send(TableLink.STATE, slot, back.state());
            table.setAway(back.seat, false);                    // offers the head of its queue again
        }
    }

    /** Everyone who said hello sits down at random seats; the bots take the rest; the ready check starts. */
    private void seatAll() {
        List<Integer> guests;
        boolean alone;
        synchronized (this) {
            if (state != State.FORMING) return;
            alone = hellos.isEmpty();
            state = alone ? State.CANCELLED : State.READY;
        }
        if (alone) {                                             // nobody else came: an ordinary table with bots
            System.out.println("[mtga] FD premier host: no guest said hello, playing with bots");
            local.host = null;
            end(0);
            local.botTable();
            return;
        }
        synchronized (this) {
            List<Integer> seats = new ArrayList<>();
            for (int i = 0; i < PremierDrafts.POD; i++) seats.add(i);
            Collections.shuffle(seats, premier.rnd);
            guests = new ArrayList<>(hellos.keySet());
            seatOf.put(0, seats.get(0));
            for (int i = 0; i < guests.size(); i++) seatOf.put(guests.get(i), seats.get(i + 1));
            List<PublicProfile> ps = new ArrayList<>();
            for (int s = 0; s < PremierDrafts.POD; s++) {
                Integer slot = slotAt(s);
                if (slot == null) ps.add(premier.profile.forAi());
                else ps.add(slot == 0 ? premier.profile.self() : hellos.get(slot));
            }
            players = ps;
        }
        System.out.println("[mtga] FD premier host: " + (guests.size() + 1) + " people at the table, seats " + seatOf);
        local.seated(seatOf.get(0), players, PremierDrafts.READY_SECONDS, numReady());
        for (int slot : guests) send(TableLink.SEATED, slot, seatedFrame(slot));
        premier.push.schedule(() -> {
            synchronized (this) {
                if (state != State.READY) return;
            }
            cancel("not everyone was ready");
        }, PremierDrafts.READY_SECONDS + 2, TimeUnit.SECONDS);
    }

    private synchronized Integer slotAt(int seat) {
        for (Map.Entry<Integer, Integer> e : seatOf.entrySet()) if (e.getValue() == seat) return e.getKey();
        return null;
    }

    private synchronized JsonObject seatedFrame(int slot) {
        return obj("draftId", local.draftId, "seat", seatOf.get(slot), "players", playersJson(), "readySecs", PremierDrafts.READY_SECONDS, "ready", numReady());
    }

    private synchronized JsonArray playersJson() {
        JsonArray a = new JsonArray();
        for (PublicProfile p : players) a.add(obj("name", p.name(), "avatar", p.avatar()));
        return a;
    }

    /** Seats ready: the bots, and the people who pressed Ready. */
    private synchronized int numReady() { return PremierDrafts.POD - seatOf.size() + readySlots.size(); }

    // ---- ready check ----------------------------------------------------------------------------------------------

    /** The local player pressed Ready. */
    String localReady() {
        ready(0);
        return null;
    }

    private void ready(int slot) {
        boolean all;
        int n;
        synchronized (this) {
            if (state != State.READY || !seatOf.containsKey(slot)) return;
            readySlots.add(slot);
            n = numReady();
            all = readySlots.size() == seatOf.size();
        }
        send(TableLink.READY_COUNT, TableLink.EVERYONE, obj("n", n, "of", PremierDrafts.POD));
        local.readyCount(n);
        if (all) startDraft();
    }

    private void startDraft() {
        TableDraft d;
        try {
            d = premier.openPacks(local.spec);
        } catch (Exception e) {
            System.out.println("[mtga] FD premier host: cannot open the packs: " + e);
            e.printStackTrace(System.out);
            cancel("the host cannot open the packs");
            return;
        }
        PremierDrafts.LocalTable mine = premier.new LocalTable(local);
        DraftTable t;
        synchronized (this) {
            if (state != State.READY) return;
            state = State.DRAFTING;
            Map<Integer, DraftTable.Seat> people = new HashMap<>();
            people.put(seatOf.get(0), mine);
            for (Map.Entry<Integer, Integer> e : seatOf.entrySet()) {
                if (e.getKey() == 0) continue;
                RemoteSeat r = new RemoteSeat(e.getKey(), e.getValue());
                remotes.put(e.getKey(), r);
                people.put(e.getValue(), r);
            }
            t = new DraftTable(d, people, premier::shown);
            table = t;
        }
        mine.table = t;
        int[] pace = PremierDrafts.botPace();
        t.setBotPace(pace[0], pace[1]);
        for (RemoteSeat r : remotes.values()) t.setGrace(r.seat, GRACE_SECONDS);
        local.attach(t, mine);
        System.out.println("[mtga] FD premier host: table " + t.id() + " starts, " + seatOf.size() + " people, seats " + seatOf);
        premier.push.schedule(() -> {
            t.start();
            for (RemoteSeat r : remotes.values()) if (r.gone) t.setAway(r.seat, true);   // went away during the ready check's last moment
        }, 600, TimeUnit.MILLISECONDS);
    }

    /** The table will not be drafted: every guest is told, the local player too. */
    private void cancel(String why) {
        synchronized (this) {
            if (state == State.CANCELLED || state == State.OVER || state == State.DRAFTING) return;
            state = State.CANCELLED;
        }
        send(TableLink.CLOSE, TableLink.EVERYONE, obj("why", why));
        local.cancelled(why);
        end(500);
    }

    // ---- during the draft -----------------------------------------------------------------------------------------

    private void guestGone(int slot) {
        RemoteSeat r;
        synchronized (this) {
            if (state == State.FORMING) {
                hellos.remove(slot);
                return;
            }
            if (state == State.READY) {
                if (!seatOf.containsKey(slot)) return;
            } else {
                r = remotes.get(slot);
                if (r == null || state != State.DRAFTING) return;
                r.gone = true;
                if (r.done) return;
                System.out.println("[mtga] FD premier host: slot " + slot + " (seat " + r.seat + ") went away");
                table.setAway(r.seat, true);
                return;
            }
        }
        cancel("a player left the table");
    }

    /** The local player left the table (dropped the event, another queue): their seat picks like a bot if others still draft. */
    void localLeft() {
        DraftTable t;
        synchronized (this) {
            if (state == State.OVER || state == State.CANCELLED) return;
            t = table;
        }
        if (t == null) {
            cancel("the host left the table");
            return;
        }
        if (othersStillDrafting() == 0) {
            if (!t.isOver()) t.close();
            end(0);
            return;
        }
        t.setAway(seatOf.get(0), true);
    }

    /** Guests who have not made their last pick. */
    synchronized int othersStillDrafting() {
        if (state != State.DRAFTING) return state == State.READY ? seatOf.size() - 1 : 0;
        int n = 0;
        for (RemoteSeat r : remotes.values()) if (!r.done) n++;
        return n;
    }

    /** Some seat picked: the pack counts go to every guest, at most every {@link #QUEUES_MS}. */
    private void queuesChanged() {
        synchronized (this) {
            if (queuesPending) return;
            queuesPending = true;
        }
        premier.push.schedule(() -> {
            DraftTable t;
            synchronized (this) {
                queuesPending = false;
                t = table;
            }
            if (t == null) return;
            JsonArray q = new JsonArray();
            for (int n : t.queueSizes()) q.add(n);
            send(TableLink.QUEUES, TableLink.EVERYONE, obj("q", q));
        }, QUEUES_MS, TimeUnit.MILLISECONDS);
    }

    /** Every seat is done: the bots' decks go to every guest, then the room closes. */
    private void over(DraftTable t) {
        synchronized (this) {
            if (state == State.OVER) return;
            state = State.OVER;
        }
        send(TableLink.OVER, TableLink.EVERYONE, obj("botDecks", DraftEvents.decksJson(t.botDecks(), premier.db)));
        System.out.println("[mtga] FD premier host: table " + t.id() + " over");
        end(1500);
    }

    private void end(long delayMs) {
        premier.hostEnded(this);
        premier.push.schedule(() -> {
            TableLink l = link;
            if (l != null) l.close();
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void send(int type, int slot, JsonObject body) {
        TableLink l = link;
        if (l != null && !l.isClosed()) l.trySend(type, slot, body);
    }

    /** A guest's seat: the table's callbacks become frames to its slot. */
    private final class RemoteSeat implements DraftTable.Seat {
        final int slot, seat;
        private volatile List<PaperCard> offer = List.of();
        volatile boolean done, gone;
        volatile List<Integer> pool = List.of();

        RemoteSeat(int slot, int seat) {
            this.slot = slot;
            this.seat = seat;
        }

        boolean pick(int grp) {
            for (PaperCard c : offer) if (premier.grp(c) == grp) return table.pick(seat, c);
            return false;
        }

        void reserve(int grp) {
            PaperCard card = null;
            for (PaperCard c : offer) if (premier.grp(c) == grp) card = c;
            table.reserve(seat, card);
        }

        /** What a reconnecting guest needs before its next pack. */
        JsonObject state() {
            gone = false;
            return obj("draftId", local.draftId, "seat", seat, "players", playersJson(), "picks", DraftEvents.grpArray(premier.grps(table.picks(seat))),
                    "done", done, "pool", DraftEvents.grpArray(pool));
        }

        @Override
        public void pack(DraftTable t, List<PaperCard> cards, int packNo, int pickNo, int seconds) {
            offer = cards;
            send(TableLink.PACK, slot, obj("cards", DraftEvents.grpArray(premier.grps(cards)), "pack", packNo, "pick", pickNo, "secs", seconds,
                    "dir", t.passesUp() ? "POS" : "NEG"));
        }

        @Override
        public void autoPicked(DraftTable t, PaperCard card) {
            offer = List.of();
            send(TableLink.AUTO, slot, obj("grp", premier.grp(card)));
        }

        @Override
        public void moved(DraftTable t) { queuesChanged(); }

        @Override
        public void done(DraftTable t, List<PaperCard> picks) {
            offer = List.of();
            pool = premier.grps(picks);
            done = true;
            send(TableLink.DONE, slot, obj("pool", DraftEvents.grpArray(pool)));
            System.out.println("[mtga] FD premier host: seat " + seat + " done (" + pool.size() + " cards)");
        }

        @Override
        public void tableOver(DraftTable t) { over(t); }
    }
}

package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.StaticData;
import forge.card.CardEdition;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.mtga.draft.Cubes;
import forge.mtga.draft.DraftTable;
import forge.mtga.draft.TableDraft;
import forge.mtga.draft.TableLink;
import forge.mtga.forge.CardDb;
import forge.mtga.net.Capture;
import forge.mtga.profile.PublicProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * Premier drafts: Arena's table draft with other players ({@code PremierDraft_<KEY>}), through the client's own human-draft
 * flow: the table queue screen (EventJoinDraftQueue, PodNotification, a ready check), the draft scene fed by
 * DraftNotification pushes (TableInfo, PickReq, PackInfo) and EventPlayerDraftMakePick, then the pool grant
 * (DraftCompleteDraft + EventPlayerDraftConfirmCardPoolGrant) and the deck builder. Matches are best-of-one, 7 wins or 3
 * losses as a quick draft, against other players of the same event through the coordinator's queue when online play is
 * on (any two players of the event, not only the same table), else against the table's bot decks.
 * <p>
 * Two events: the newest Standard set Forge can draft, and a cube (the newest "MTGO Vintage Cube" draft file).
 * {@code -Dmtga.premier.set=<code>} / {@code -Dmtga.premier.cube=<draft file name>} choose others.
 * <p>
 * The table: with online play the coordinator's table queue seats up to 8 people (coordinator/src/tables.ts); the first
 * one's server hosts the {@link DraftTable} ({@link PremierHost}), the others play their seat through it
 * ({@link PremierGuest}); bots take the empty seats. Offline, or alone at the table, the table runs here with 7 bots.
 * The local seat ({@link Local}) turns either into the client's pushes the same way.
 */
final class PremierDrafts {
    static final String PREFIX = "PremierDraft_";
    static final String CUBE_KEY = "VCUBE";
    /** Seconds the players have to press Ready once the table is full (-Dmtga.premier.readySecs for tests). */
    static final int READY_SECONDS = Integer.getInteger("mtga.premier.readySecs", 30);
    static final int POD = 8;
    /** Course field of a guest's seat ({base, table, slot, token, draftId}): a restarted server rejoins the table with it. */
    private static final String SEAT_FIELD = "PremierSeat";

    /** One premier event: a set (by code) or a cube (by draft file name). */
    record Spec(String key, String setCode, String cubeFile, String title, int collation, List<Integer> poolGrpIds) {
        String eventName() { return PREFIX + key; }

        boolean isCube() { return cubeFile != null; }

        /** What both ends of a table must have the same: the product (set code / cube file). */
        String product() { return isCube() ? cubeFile : setCode; }

        /** The table queue's protocol field: frame version + the product, so different products never meet. */
        String queueVersion() {
            return TableLink.VERSION + "." + key + "." + Integer.toHexString(product().hashCode());
        }
    }

    /** Online settings (from the Front Door's peer settings): coordinator address, card DB fingerprint, friend code. */
    record Online(String base, String fingerprint, String code) { }

    final CardDb db;
    final DraftEvents events;
    final ProfileApi profile;
    private final Supplier<Online> online;
    final Random rnd = new Random();
    /** Pushes go out on one thread, a moment after the reply of the request that caused them (the client handles a pick's reply first). */
    final ScheduledExecutorService push = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fd-draft-push");
        t.setDaemon(true);
        return t;
    });
    private List<Spec> specs;
    /** The table being formed or drafted, at most one (one local player). */
    private volatile Local local;
    /** The draft id of the last table that was cancelled (the client's pod may still carry it). */
    private volatile String lastCancelled;
    /** A table this server hosts; it outlives the local seat while other players still draft. */
    private volatile PremierHost hosted;

    PremierDrafts(CardDb db, DraftEvents events, ProfileApi profile) { this(db, events, profile, () -> null); }

    PremierDrafts(CardDb db, DraftEvents events, ProfileApi profile, Supplier<Online> online) {
        this.db = db;
        this.events = events;
        this.profile = profile;
        this.online = online;
    }

    static boolean isPremier(String eventName) { return eventName != null && eventName.startsWith(PREFIX); }

    // ---- events ---------------------------------------------------------------------------------------------------

    synchronized List<Spec> specs() {
        if (specs != null) return specs;
        List<Spec> out = new ArrayList<>();
        Spec set = setSpec();
        if (set != null) out.add(set);
        Spec cube = cubeSpec();
        if (cube != null) out.add(cube);
        specs = out;
        System.out.println("[mtga] FD premier drafts: " + out.stream().map(s -> s.eventName() + " (" + s.title() + ", " + s.poolGrpIds().size() + " cards)").toList());
        return specs;
    }

    /** The newest draftable set in Standard (Forge's Standard, the quick-draft catalog's coverage rule). */
    private Spec setSpec() {
        String forced = System.getProperty("mtga.premier.set");
        Set<String> standard = new LinkedHashSet<>();
        try {
            GameFormat std = FModel.getFormats().getStandard();
            if (std != null) standard.addAll(std.getAllowedSetCodes());
        } catch (Exception e) {
            System.out.println("[mtga] FD premier: no Standard format (" + e + ")");
        }
        for (DraftEvents.SetInfo s : events.catalog()) {
            boolean pick = forced != null && !forced.isBlank() ? s.code().equalsIgnoreCase(forced.trim())
                    : standard.contains(s.code()) && StaticData.instance().getEditions().get(s.code()).getType() == CardEdition.Type.EXPANSION;
            if (pick) return new Spec(s.code(), s.code(), null, s.name(), CollationIds.of(s.code()), s.grpIds());
        }
        System.out.println("[mtga] FD premier: no Standard set to draft" + (forced != null ? " (asked for " + forced + ")" : ""));
        return null;
    }

    /** The cube: -Dmtga.premier.cube, else the newest "MTGO Vintage Cube" draft file. */
    private Spec cubeSpec() {
        String file = Cubes.premierCube();
        List<PaperCard> cards = Cubes.cards(file);
        if (cards.isEmpty()) {
            System.out.println("[mtga] FD premier: no cube " + file);
            return null;
        }
        List<Integer> grps = new ArrayList<>();
        int missing = 0;
        for (PaperCard c : cards) {
            int g = db.grpId(c.getName());
            if (g != 0) grps.add(g);
            else missing++;
        }
        if (missing > 0) System.out.println("[mtga] FD premier: cube " + file + ": " + missing + " cards have no client card (run the card DB step again)");
        return new Spec(CUBE_KEY, null, file, "Vintage Cube", CollationIds.of("POWEREDCUBE"), grps);
    }

    Spec spec(String eventName) {
        for (Spec s : specs()) if (s.eventName().equalsIgnoreCase(eventName)) return s;
        return null;
    }

    /** EventInfoV3 entries for EventGetActiveEventsV2.Events, listed before the quick drafts. */
    JsonArray eventInfos() {
        JsonArray out = new JsonArray();
        int priority = 1;
        for (Spec s : specs()) {
            String name = s.eventName();
            JsonArray collations = new JsonArray();
            for (int i = 0; i < 3; i++) collations.add(s.collation());
            JsonObject data = obj(
                    "BoosterPacksDisplay", obj("CardGrantTime", "PreMatch", "CollationIds", collations),
                    "DraftScreenUI", obj("TimeoutWarnSec", 10, "TimeoutDiscoSec", 0),
                    "TitleRankText", obj("LocKey", "Events/Event_Title_" + name),
                    "DescriptionText", obj("LocKey", "Events/Event_Desc_" + name),
                    "ResignWidget", obj(),
                    "LossDetailsDisplay", obj("Games", DraftEvents.MAX_LOSSES, "LossDetailsType", "LossSlots"),
                    "MainButtonWidget", obj(),
                    "SelectedDeckWidget", obj("DeckButtonBehavior", "Editable", "ShowCopyDeckButton", true),
                    "ByCourseObjectiveTrack", DraftEvents.winTrack(),
                    "ViewCardPoolWidget", obj("CardPool", DraftEvents.grpArray(s.poolGrpIds())));
            JsonObject ux = obj("PublicEventName", name, "DisplayPriority", priority++, "PrioritizeBannerIfPlayerHasToken", false,
                    "Parameters", obj(), "Group", "", "EventBladeBehavior", "EventPage", "DynamicFilterTagIds", new JsonArray(),
                    "DeckSelectFormat", ForgeFormats.LIMITED, "FactionSealedUXInfo", new JsonArray(), "Prizes", DraftEvents.prizes(),
                    "EventComponentData", data, "Joinability", "Open");
            out.add(obj("InternalEventName", name, "EventState", "Active", "FormatType", "Draft",
                    "StartTime", "2020-01-01T00:00:00Z", "LockedTime", "2099-01-01T00:00:00Z", "ClosedTime", "2099-01-01T00:00:00Z",
                    "Flags", new JsonArray(), "PastEntries", obj(), "EntryFees", new JsonArray(), "EventTags", new JsonArray(),
                    "EventUXInfo", ux, "WinCondition", "SingleElimination"));
        }
        return out;
    }

    // ---- handlers -------------------------------------------------------------------------------------------------

    /** EventJoin: enter the event (free), landing in the table draft. */
    Object join(JsonObject req) {
        String name = str(req, "EventName");
        if (spec(name) == null) return new FrontDoor.Err(7000, "unknown event " + name);
        JsonObject c = events.course(name);
        if (c == null) {
            c = events.newCourse(name, "PlayerDraft");
            events.save(c);
        }
        return obj("Course", c, "InventoryInfo", null);
    }

    /** EventJoinDraftQueue: look for a table (the coordinator's table queue online, else seven bots at once), then the ready check. */
    Object joinQueue(Consumer<String> pushTo, JsonObject req) {
        String name = str(req, "EventName");
        Spec spec = spec(name);
        if (spec == null) return new FrontDoor.Err(7000, "unknown event " + name);
        Local old = local;
        if (old != null) old.close();
        Local l = new Local(pushTo, spec, java.util.UUID.randomUUID().toString());
        local = l;
        Online o = online.get();
        System.out.println("[mtga] FD premier " + name + ": looking for a table" + (o == null ? " (offline: bots)" : " at " + o.base()));
        if (o == null) l.botTable();
        else l.queue(o);
        return module();
    }

    /** EventLeaveDraftQueue: leave before the draft starts. */
    Object leaveQueue() {
        Local l = local;
        if (l != null && l.table == null) {
            l.close();
            local = null;
        }
        return module();
    }

    /** EventPlayerDraftReadyPlayer{EventName, DraftId}: the player is ready; the draft starts when everyone at the table is. */
    Object ready(JsonObject req) {
        String name = str(req, "EventName");
        Local l = local;
        if (l == null || !l.spec.eventName().equals(name) || !l.draftId.equals(str(req, "DraftId"))) return new FrontDoor.Err(7000, "no table for " + name);
        JsonObject c = events.course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        String problem = l.ready();
        if (problem != null) return new FrontDoor.Err(7000, problem);
        c.addProperty("DraftId", l.draftId);
        events.save(c);
        return c;
    }

    /** EventJoinDraft (rejoin after the client or this server restarted): the course, and the table state pushed again. */
    Object rejoin(Consumer<String> pushTo, JsonObject req) {
        String name = str(req, "EventName");
        JsonObject c = events.course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        Local l = local;
        if (l != null && l.spec.eventName().equals(name) && (l.table != null || l.guest != null)) {
            l.session = pushTo;
            push.schedule(l::resend, 500, TimeUnit.MILLISECONDS);
            return c;
        }
        Spec spec = spec(name);
        Online o = online.get();
        JsonElement seat = c.get(SEAT_FIELD);
        if (spec != null && o != null && seat != null && seat.isJsonObject() && c.get("DraftId") != null && !c.get("DraftId").isJsonNull()) {
            JsonObject s = seat.getAsJsonObject();          // this server restarted while its player was a guest: back to the host's table
            Local back = new Local(pushTo, spec, str(s, "draftId"));
            local = back;
            back.rejoinTable(o, new TableLink.Seated(str(s, "base"), str(s, "table"), s.get("slot").getAsInt(), str(s, "token"), 0, List.of()));
            System.out.println("[mtga] FD premier " + name + ": rejoining table " + str(s, "table"));
            return c;
        }
        String stale = c.get("DraftId") != null && !c.get("DraftId").isJsonNull() ? c.get("DraftId").getAsString() : lastCancelled;
        c.add("DraftId", null);           // the table is gone: back to looking for one
        c.remove(SEAT_FIELD);
        events.save(c);
        if (spec != null && stale != null) {                // the client's pod still thinks it drafts: back to "Start Draft"
            Local gone = new Local(pushTo, spec, stale);
            push.schedule(gone::reopen, 500, TimeUnit.MILLISECONDS);
        }
        return c;
    }

    /** EventPlayerDraftMakePick{DraftId, GrpIds, Pack, Pick}. */
    Object pick(JsonObject req) {
        Local l = local;
        if (l == null || l.view == null || !l.draftId.equals(str(req, "DraftId"))) return new FrontDoor.Err(7000, "no draft " + str(req, "DraftId"));
        JsonElement ids = req.get("GrpIds");
        int grp = ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() > 0 ? ids.getAsJsonArray().get(0).getAsInt() : 0;
        boolean ok = l.offer.contains(grp) && l.view.pick(grp);
        if (!ok) {
            System.out.println("[mtga] FD premier: pick " + grp + " refused");
            return obj("IsPickingCompleted", false, "IsPickSuccessful", false, "TableInfo", l.tableInfo(), "PickInfo", l.pickInfo(), "PackInfo", l.packInfo());
        }
        return obj("IsPickingCompleted", l.done, "IsPickSuccessful", true, "TableInfo", null, "PickInfo", null, "PackInfo", null);
    }

    /** EventPlayerDraftReserveCard / EventPlayerDraftClearReservedCard: the card the pick timer takes. */
    Object reserve(JsonObject req, boolean clear) {
        Local l = local;
        if (l == null || l.view == null) return new FrontDoor.Err(7000, "no draft");
        int grp = 0;
        if (!clear) {
            JsonElement ids = req.get("GrpIds");
            grp = ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() > 0 ? ids.getAsJsonArray().get(0).getAsInt() : 0;
            if (!l.offer.contains(grp)) grp = 0;
        }
        l.view.reserve(grp);
        return obj("IsPickingCompleted", false, "IsPickSuccessful", true, "TableInfo", null, "PickInfo", null, "PackInfo", null);
    }

    /** EventPlayerDraftGetTablePacks{DraftId}: packs waiting per seat (booster art per pack). */
    Object tablePacks(JsonObject req) {
        Local l = local;
        if (l == null || l.view == null) return new FrontDoor.Err(7000, "no draft");
        JsonArray bySeat = new JsonArray();
        for (int q : l.view.queueSizes()) bySeat.add(l.packs(q));
        return obj("PacksBySeat", bySeat);
    }

    /** DraftCompleteDraft: the pool goes into the course (deck building next). */
    Object complete(JsonObject req) {
        String name = str(req, "EventName");
        JsonObject c = events.course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        Local l = local;
        if (l != null && l.spec.eventName().equals(name) && l.pool != null) {
            JsonArray pool = new JsonArray();
            for (int g : l.pool) if (g != 0) pool.add(g);
            c.add("CardPool", pool);
            c.addProperty("CurrentModule", "DeckSelect");
            c.add("DraftId", null);
            c.remove(SEAT_FIELD);
            JsonArray bots = l.view == null ? null : l.view.botDecks();
            if (bots != null) c.add("BotDecks", bots);          // else saved when the table ends
            events.save(c);
            System.out.println("[mtga] FD premier " + name + ": draft done, " + pool.size() + " cards in the pool");
        }
        return c;
    }

    /** EventPlayerDraftConfirmCardPoolGrant: nothing to grant offline (the pool is in the course already). */
    Object confirmPool(JsonObject req) {
        JsonObject c = events.course(str(req, "EventName"));
        return c == null ? new FrontDoor.Err(7000, "not in event") : c;
    }

    /** EventDrop / EventResign of a premier event: leave its table too. */
    void dropped(String eventName) {
        Local l = local;
        if (l != null && l.spec.eventName().equals(eventName)) {
            l.close();
            local = null;
        }
    }

    /** People at the table this server hosts who have not made their last pick yet (0 = nobody depends on this server). */
    int othersStillDrafting() {
        PremierHost h = hosted;
        return h == null ? 0 : h.othersStillDrafting();
    }

    void hosting(PremierHost h) { hosted = h; }

    void hostEnded(PremierHost h) { if (hosted == h) hosted = null; }

    /** Milliseconds a bot takes per pick: -Dmtga.premier.botPace=min-max (default 1500-4500; 0 = instant). */
    static int[] botPace() {
        String v = System.getProperty("mtga.premier.botPace", "1500-4500").trim();
        try {
            String[] p = v.split("-");
            int min = Integer.parseInt(p[0].trim()), max = p.length > 1 ? Integer.parseInt(p[1].trim()) : min;
            return new int[] {min, max};
        } catch (Exception e) {
            return new int[] {1500, 4500};
        }
    }

    /** The packs of the event (throws when the product cannot be opened). */
    TableDraft openPacks(Spec spec) {
        return spec.isCube() ? TableDraft.forCube(spec.cubeFile(), c -> db.grpId(c.getName()) != 0)
                : TableDraft.forSet(StaticData.instance().getEditions().get(spec.setCode()));
    }

    boolean shown(PaperCard c) { return db.grpId(c.getName()) != 0; }

    int grp(PaperCard c) { return db.grpId(c.getName()); }

    List<Integer> grps(List<PaperCard> cards) {
        List<Integer> out = new ArrayList<>(cards.size());
        for (PaperCard c : cards) out.add(grp(c));
        return out;
    }

    private static JsonObject module() {
        return obj("CurrentModule", "PlayerDraft", "Payload", "", "DTO_InventoryInfo", null);
    }

    // ---- the table as the local seat sees it ----------------------------------------------------------------------

    /** The draft table from the local seat: here ({@link LocalTable}) or on the host's server ({@link PremierGuest}). Cards are GrpIds. */
    interface TableView {
        int[] queueSizes();

        /** Packs pass to seat+1 this round. */
        boolean passesUp();

        /** This seat's picks so far. */
        List<Integer> picks();

        /** Pick a card of the offered pack; false = refused. */
        boolean pick(int grp);

        /** The card the timer takes (0 clears). */
        void reserve(int grp);

        /** The bots' decks (DraftEvents.decksJson) once the table is over, else null. */
        JsonArray botDecks();
    }

    /** The local seat at a table running in this server: Forge cards to GrpIds both ways. */
    final class LocalTable implements TableView, DraftTable.Seat {
        final Local seat;
        volatile DraftTable table;
        private volatile List<PaperCard> offer = List.of();

        LocalTable(Local seat) { this.seat = seat; }

        @Override public int[] queueSizes() { return table.queueSizes(); }

        @Override public boolean passesUp() { return table.passesUp(); }

        @Override public List<Integer> picks() { return grps(table.picks(seat.seat)); }

        @Override
        public boolean pick(int grp) {
            for (PaperCard c : offer) if (grp(c) == grp) return table.pick(seat.seat, c);
            return false;
        }

        @Override
        public void reserve(int grp) {
            PaperCard card = null;
            for (PaperCard c : offer) if (grp(c) == grp) card = c;
            table.reserve(seat.seat, card);
        }

        @Override
        public JsonArray botDecks() { return table.isOver() ? DraftEvents.decksJson(table.botDecks(), db) : null; }

        @Override
        public void pack(DraftTable t, List<PaperCard> cards, int packNo, int pickNo, int seconds) {
            offer = cards;
            seat.pack(grps(cards), packNo, pickNo, seconds);
        }

        @Override
        public void autoPicked(DraftTable t, PaperCard card) { seat.autoPicked(grp(card)); }

        @Override
        public void moved(DraftTable t) { seat.moved(); }

        @Override
        public void done(DraftTable t, List<PaperCard> pool) {
            offer = List.of();
            seat.done(grps(pool));
        }

        @Override
        public void tableOver(DraftTable t) { seat.tableOver(DraftEvents.decksJson(t.botDecks(), db)); }
    }

    // ---- the local player's seat ----------------------------------------------------------------------------------

    /** The local player's place: the queue, the ready check, then their seat at a table, as the client's pushes. */
    final class Local {
        volatile Consumer<String> session;
        final Spec spec;
        final String draftId;
        volatile int seat = rnd.nextInt(POD);
        volatile List<PublicProfile> players = new ArrayList<>();
        /** The table running here (offline, alone or hosting), else null. */
        volatile DraftTable table;
        /** What the handlers use once the draft runs. */
        volatile TableView view;
        volatile PremierHost host;
        volatile PremierGuest guest;
        private volatile TableLink.Ticket ticket;
        volatile boolean ready, done, tableSent, closed;
        volatile List<Integer> pool;
        /** The pack offered for the pending pick (GrpIds), and its numbers. */
        volatile List<Integer> offer = List.of();
        volatile int packNo = 1, pickNo = 1, seconds;
        private int[] lastQueues;

        Local(Consumer<String> session, Spec spec, String draftId) {
            this.session = session;
            this.spec = spec;
            this.draftId = draftId;
        }

        // ---- finding a table ----

        /** Offline (or alone at the table): seven bots sit down at once, then the ready check. */
        void botTable() {
            List<PublicProfile> ps = new ArrayList<>();
            for (int i = 0; i < POD; i++) ps.add(i == seat ? profile.self() : profile.forAi());
            players = ps;
            for (int n = 1; n <= POD; n++) {
                int filled = n;
                push.schedule(() -> pod(filled), 300L + 250L * n, TimeUnit.MILLISECONDS);
            }
            push.schedule(this::botReadyCheck, 300L + 250L * POD + 400, TimeUnit.MILLISECONDS);
        }

        /** Online: wait in the coordinator's table queue; the table forms when it is full or its deadline passes. */
        void queue(Online o) {
            PublicProfile me = profile.self();
            TableLink.Ticket t = TableLink.queue(o.base(), spec.eventName(), o.fingerprint(), spec.queueVersion(), me.playerId(), me.name(), o.code(), this::pod);
            ticket = t;
            t.result().whenComplete((s, e) -> {
                if (closed || local != this) return;
                if (e != null) {
                    if (t.result().isCancelled()) return;
                    System.out.println("[mtga] FD premier " + spec.eventName() + ": table queue failed (" + e.getMessage() + "), playing with bots");
                    botTable();
                    return;
                }
                System.out.println("[mtga] FD premier " + spec.eventName() + ": table " + s.table() + " formed, " + s.people() + " people, slot " + s.slot());
                if (s.people() <= 1) botTable();
                else if (s.host()) {
                    PremierHost h = new PremierHost(PremierDrafts.this, this, s, o);
                    host = h;
                    hosting(h);
                    h.start();
                } else {
                    PremierGuest g = new PremierGuest(PremierDrafts.this, this, s, o, false);
                    guest = g;
                    g.start();
                }
            });
        }

        /** This server restarted while its player sat at another player's table: reconnect to the seat. */
        void rejoinTable(Online o, TableLink.Seated s) {
            ready = true;
            PremierGuest g = new PremierGuest(PremierDrafts.this, this, s, o, true);
            guest = g;
            view = g;
            g.start();
        }

        void pod(int filled) {
            if (closed || view != null) return;
            send(obj("Type", "PodNotification", "PodQueueNotification", obj("EventId", spec.eventName(), "NumInPod", filled,
                    "PodCapacity", POD, "PlayerDetails", new JsonArray())));
        }

        private void botReadyCheck() {
            if (closed || view != null) return;
            draft("ReadyReq", obj("TimeoutSec", READY_SECONDS, "NumReady", POD - 1));
            draft("ReadyUpdate", obj("NumReady", POD - 1));          // the bots are ready at once
            push.schedule(() -> {
                if (!ready && !closed && local == this) cancelled("not ready in time");
            }, READY_SECONDS + 2, TimeUnit.SECONDS);
        }

        /** Online: the host gave this seat its place; the table fills with bots and the ready check starts. */
        void seated(int seat, List<PublicProfile> players, int readySecs, int numReady) {
            if (closed) return;
            this.seat = seat;
            this.players = players;
            pod(POD);
            draft("ReadyReq", obj("TimeoutSec", readySecs, "NumReady", numReady));
            draft("ReadyUpdate", obj("NumReady", numReady));
        }

        void readyCount(int numReady) { if (!closed) draft("ReadyUpdate", obj("NumReady", numReady)); }

        /** The table will not start (someone was not ready, the host left): the client goes back to its event page. */
        void cancelled(String why) {
            if (closed) return;
            System.out.println("[mtga] FD premier " + spec.eventName() + ": table cancelled (" + why + ")");
            session.accept(notification("Close", obj()).toString());
            lastCancelled = draftId;
            push.schedule(this::reopen, 1000, TimeUnit.MILLISECONDS);
            JsonObject c = events.course(spec.eventName());
            if (c != null && (c.has(SEAT_FIELD) || c.get("DraftId") != null && !c.get("DraftId").isJsonNull())) {
                c.add("DraftId", null);
                c.remove(SEAT_FIELD);
                events.save(c);
            }
            close();
            if (local == this) local = null;
        }

        /**
         * After a Close the client's draft pod is in state None: its event page offers "Rejoin Draft", which does nothing.
         * A ReadyUpdate puts the pod back in Podmaking, so the page offers "Start Draft" (= the table queue) again.
         */
        void reopen() {
            String json = notification("ReadyUpdate", obj("NumReady", 0)).toString();
            Capture.log("FD push " + Capture.flat(json, 400));
            session.accept(json);
        }

        /** A guest keeps where its seat is, so a restarted server can come back to it. */
        void keepSeat(TableLink.Seated s) {
            JsonObject c = events.course(spec.eventName());
            if (c == null) return;
            c.add(SEAT_FIELD, obj("base", s.base(), "table", s.table(), "slot", s.slot(), "token", s.token(), "draftId", draftId));
            events.save(c);
        }

        /** Ready pressed (null = fine, else the problem). */
        String ready() {
            if (view != null || ready) return null;
            ready = true;
            if (host != null) return host.localReady();
            if (guest != null) return guest.ready();
            TableDraft d;
            try {
                d = openPacks(spec);
            } catch (Exception e) {
                System.out.println("[mtga] FD premier " + spec.eventName() + ": cannot open the packs: " + e);
                e.printStackTrace(System.out);
                return "cannot open the packs for " + spec.title();
            }
            draft("ReadyUpdate", obj("NumReady", POD));
            LocalTable lt = new LocalTable(this);
            DraftTable t = new DraftTable(d, Map.of(seat, lt), PremierDrafts.this::shown);
            lt.table = t;
            int[] pace = botPace();
            t.setBotPace(pace[0], pace[1]);
            attach(t, lt);
            System.out.println("[mtga] FD premier " + spec.eventName() + ": table " + t.id() + ", seat " + seat + " of " + t.podSize());
            push.schedule(t::start, 600, TimeUnit.MILLISECONDS);
            return null;
        }

        /** The draft starts: the handlers talk to {@code view} from now on ({@code table} = it runs here). */
        void attach(DraftTable table, TableView view) {
            this.table = table;
            this.view = view;
        }

        // ---- the table tells ----

        void pack(List<Integer> cards, int packNo, int pickNo, int seconds) {
            this.offer = cards;
            this.packNo = packNo;
            this.pickNo = pickNo;
            this.seconds = seconds;
            if (!tableSent) {
                tableSent = true;
                draft("TableInfo", obj("TableInfo", tableInfo(), "PickInfo", pickInfo(), "PackInfo", packInfo()));
            } else {
                draft("PickReq", obj("PickInfo", pickInfo(), "PackInfo", packInfo()));
            }
        }

        void autoPicked(int grp) { System.out.println("[mtga] FD premier: picked for the player: " + grp); }

        void moved() {
            TableView v = view;
            if (!tableSent || v == null) return;
            int[] q = v.queueSizes();
            int self = q[seat], neg = q[Math.floorMod(seat - 1, POD)], pos = q[Math.floorMod(seat + 1, POD)];
            int[] now = {self, neg, pos};
            if (Arrays.equals(now, lastQueues)) return;
            lastQueues = now;
            draft("PackInfo", obj("PackInfo", packInfo()));
        }

        void done(List<Integer> pool) {
            this.pool = pool;
            this.done = true;
            this.offer = List.of();
            System.out.println("[mtga] FD premier " + spec.eventName() + ": last pick made (" + pool.size() + " cards)");
        }

        /** Every seat is done: keep the bots' decks as the offline opponents (if the pool is in the course already). */
        void tableOver(JsonArray botDecks) {
            JsonObject c = events.course(spec.eventName());
            if (c == null || c.has("BotDecks") && c.get("BotDecks").isJsonArray() || botDecks == null) return;
            c.add("BotDecks", botDecks);
            events.save(c);
        }

        /** A guest whose host is gone in the middle of the draft: the table is over for it, without a pool. */
        void hostGone() {
            if (done) return;
            cancelled("the host left the table");
        }

        /** The whole state again (rejoin): the next pack comes with the table. */
        void resend() {
            if (view == null) return;
            if (offer.isEmpty()) tableSent = false;                 // a reconnecting guest: the host sends the pack again
            else draft("TableInfo", obj("TableInfo", tableInfo(), "PickInfo", pickInfo(), "PackInfo", packInfo()));
        }

        JsonObject tableInfo() {
            JsonArray ps = new JsonArray();
            for (PublicProfile p : players) ps.add(obj("ScreenName", p.name(), "Avatar", p.avatar().isEmpty() ? "UNKNOWN" : p.avatar()));
            JsonArray picked = new JsonArray(), styles = new JsonArray();
            TableView v = view;
            if (v != null) for (int g : v.picks()) if (g != 0) picked.add(g);
            return obj("Players", ps, "SelfSeat", seat, "SelfPack", packNo, "PickedCards", picked, "PickedStyles", styles);
        }

        JsonObject pickInfo() {
            JsonArray cards = new JsonArray(), styles = new JsonArray();
            for (int g : offer) cards.add(g);
            TableView v = view;
            boolean up = v == null || v.passesUp();
            return obj("PackCards", cards, "NumCardsToPick", 1, "SuggestedCards", new JsonArray(), "TimeoutSec", seconds,
                    "PassDirection", up ? "POS" : "NEG", "SelfPack", packNo, "SelfPick", pickNo, "PackStyles", styles);
        }

        JsonObject packInfo() {
            TableView v = view;
            int[] q = v == null ? new int[POD] : v.queueSizes();
            return obj("SelfPacks", packs(q[seat]), "NegNeighborPacks", packs(q[Math.floorMod(seat - 1, POD)]),
                    "PosNeighborPacks", packs(q[Math.floorMod(seat + 1, POD)]));
        }

        JsonArray packs(int n) {
            JsonArray a = new JsonArray();
            for (int i = 0; i < n; i++) a.add(spec.collation());
            return a;
        }

        private void draft(String type, JsonObject fields) {
            send(notification(type, fields));
        }

        private JsonObject notification(String type, JsonObject fields) {
            JsonObject n = obj("DraftId", draftId, "Type", type, "EventName", spec.eventName(), "TimeoutSec", 0, "NumReady", 0,
                    "TableInfo", null, "PickInfo", null, "PackInfo", null);
            for (Map.Entry<String, JsonElement> e : fields.entrySet()) n.add(e.getKey(), e.getValue());
            return obj("Type", "DraftNotification", "DraftNotification", n);
        }

        private void send(JsonObject notification) {
            if (closed) return;
            String json = notification.toString();
            push.schedule(() -> {
                if (closed) return;
                Capture.log("FD push " + Capture.flat(json, 400));
                session.accept(json);
            }, 150, TimeUnit.MILLISECONDS);
        }

        /**
         * The player leaves this table. A table with other people at it keeps running without them (their seat picks
         * like a bot when hosted here; the host does the same for a guest who goes).
         */
        void close() {
            if (closed) return;
            closed = true;
            TableLink.Ticket t = ticket;
            if (t != null) t.cancel();
            PremierHost h = host;
            if (h != null) {
                h.localLeft();
                return;
            }
            PremierGuest g = guest;
            if (g != null) g.close();
            DraftTable d = table;
            if (d != null && !d.isOver()) d.close();
        }
    }
}

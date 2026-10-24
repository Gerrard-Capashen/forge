package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.mtga.draft.TableLink;
import forge.mtga.profile.PublicProfile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * The local player's seat at a table another player's server hosts ({@link PremierHost}): the host's {@link TableLink}
 * frames become the local seat's pushes, the player's picks and reserves go to the host. A pick waits for the host's
 * answer, so the pool here is always the one the host has. If the link drops, it reconnects to the same slot and the host
 * sends the seat's state (its seat picked like a bot meanwhile); once the pool is here the host no longer matters.
 */
final class PremierGuest implements TableLink.Listener, PremierDrafts.TableView {
    /** Longest wait for the host's answer to a pick. */
    private static final int PICK_WAIT_MS = 6_000;
    /** Reconnect attempts after the link dropped, this far apart. */
    private static final int RETRY_MS = 3_000, RETRY_FOR_MS = 180_000;

    private final PremierDrafts premier;
    private final PremierDrafts.Local local;
    private final TableLink.Seated seated;
    private final PremierDrafts.Online online;
    private volatile boolean rejoin;
    private volatile TableLink link;
    private volatile boolean closed, over, hostGone, started;
    private volatile String dir = "POS";
    private final List<Integer> picks = new ArrayList<>();
    private volatile int[] queues = new int[PremierDrafts.POD];
    private volatile JsonArray botDecks;
    private final AtomicInteger pickSeq = new AtomicInteger();
    private final Map<Integer, CompletableFuture<Boolean>> pending = new ConcurrentHashMap<>();

    /** @param rejoin this server restarted: the seat is taken already, ask the host for its state */
    PremierGuest(PremierDrafts premier, PremierDrafts.Local local, TableLink.Seated seated, PremierDrafts.Online online, boolean rejoin) {
        this.premier = premier;
        this.local = local;
        this.seated = seated;
        this.online = online;
        this.rejoin = rejoin;
        this.started = rejoin;
    }

    void start() { premier.push.execute(() -> connect(System.currentTimeMillis() + RETRY_FOR_MS)); }

    private void connect(long until) {
        if (closed || over || hostGone) return;
        try {
            link = TableLink.open(seated, this);
            link.start();
            System.out.println("[mtga] FD premier guest: in the room of table " + seated.table() + " (slot " + seated.slot() + ")");
        } catch (IOException e) {
            if (System.currentTimeMillis() < until && !e.getMessage().contains("410")) {
                System.out.println("[mtga] FD premier guest: " + e.getMessage() + ", trying again");
                premier.push.schedule(() -> connect(until), RETRY_MS, TimeUnit.MILLISECONDS);
            } else {
                System.out.println("[mtga] FD premier guest: cannot reach the table any more (" + e.getMessage() + ")");
                hostGone = true;
                local.hostGone();
            }
        }
    }

    // ---- what the player does -------------------------------------------------------------------------------------

    /** Ready pressed: tell the host (the draft starts when everyone is). */
    String ready() {
        send(TableLink.READY, obj());
        return null;
    }

    @Override
    public boolean pick(int grp) {
        TableLink l = link;
        if (l == null || l.isClosed()) return false;
        int n = pickSeq.incrementAndGet();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        pending.put(n, answer);
        List<Integer> offered = local.offer;
        send(TableLink.PICK, obj("n", n, "grp", grp));
        try {
            boolean ok = answer.get(PICK_WAIT_MS, TimeUnit.MILLISECONDS);
            if (ok) {
                synchronized (picks) { picks.add(grp); }
                if (local.offer == offered) local.offer = List.of();   // the host sends the next pack before its answer
            }
            return ok;
        } catch (Exception e) {
            System.out.println("[mtga] FD premier guest: no answer to pick " + n + " (" + e + ")");
            return false;
        } finally {
            pending.remove(n);
        }
    }

    @Override
    public void reserve(int grp) { send(TableLink.RESERVE, obj("grp", grp)); }

    @Override
    public int[] queueSizes() { return queues.clone(); }

    @Override
    public boolean passesUp() { return "POS".equals(dir); }

    @Override
    public List<Integer> picks() { synchronized (picks) { return new ArrayList<>(picks); } }

    @Override
    public JsonArray botDecks() { return botDecks; }

    void close() {
        closed = true;
        TableLink l = link;
        if (l != null) l.close();
    }

    // ---- what the host says ---------------------------------------------------------------------------------------

    @Override
    public void control(JsonObject m) {
        switch (str(m, "t")) {
            case "host" -> send(TableLink.HELLO, obj("v", TableLink.VERSION, "fp", online.fingerprint(), "spec", local.spec.product(),
                    "profile", premier.profile.self().toJson(), "rejoin", rejoin || started));
            case "left" -> {
                hostGone = true;
                if (!over) {
                    System.out.println("[mtga] FD premier guest: the host left the table");
                    local.hostGone();
                }
            }
            default -> { }
        }
    }

    @Override
    public void closed() {
        for (CompletableFuture<Boolean> f : pending.values()) f.complete(false);
        if (closed || over || hostGone) return;
        System.out.println("[mtga] FD premier guest: lost the table's room, reconnecting");
        premier.push.schedule(() -> connect(System.currentTimeMillis() + RETRY_FOR_MS), RETRY_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void frame(int type, int slot, JsonObject b) {
        switch (type) {
            case TableLink.SEATED -> {
                local.keepSeat(seated);
                local.seated(b.get("seat").getAsInt(), players(b.getAsJsonArray("players")), b.get("readySecs").getAsInt(), b.get("ready").getAsInt());
            }
            case TableLink.READY_COUNT -> local.readyCount(b.get("n").getAsInt());
            case TableLink.CLOSE -> {
                over = true;
                local.cancelled(str(b, "why"));
                close();
            }
            case TableLink.PACK -> {
                if (!started) {                                   // the first pack: the draft has started
                    started = true;
                    local.attach(null, this);
                }
                dir = str(b, "dir");
                local.pack(ints(b.getAsJsonArray("cards")), b.get("pack").getAsInt(), b.get("pick").getAsInt(), b.get("secs").getAsInt());
            }
            case TableLink.PICKED -> {
                CompletableFuture<Boolean> f = pending.get(b.get("n").getAsInt());
                if (f != null) f.complete(b.get("ok").getAsBoolean());
            }
            case TableLink.AUTO -> {
                int grp = b.get("grp").getAsInt();
                synchronized (picks) { picks.add(grp); }
                local.offer = List.of();
                local.autoPicked(grp);
            }
            case TableLink.QUEUES -> {
                JsonArray q = b.getAsJsonArray("q");
                int[] n = new int[PremierDrafts.POD];
                for (int i = 0; i < n.length && i < q.size(); i++) n[i] = q.get(i).getAsInt();
                queues = n;
                local.moved();
            }
            case TableLink.DONE -> local.done(ints(b.getAsJsonArray("pool")));
            case TableLink.OVER -> {
                over = true;
                botDecks = b.getAsJsonArray("botDecks");
                local.tableOver(botDecks);
                close();
            }
            case TableLink.STATE -> {
                rejoin = true;
                started = true;
                synchronized (picks) {
                    picks.clear();
                    picks.addAll(ints(b.getAsJsonArray("picks")));
                }
                local.seat = b.get("seat").getAsInt();
                local.players = players(b.getAsJsonArray("players"));
                local.tableSent = false;                          // the next pack comes with the whole table
                local.offer = List.of();
                local.attach(null, this);
                if (b.get("done").getAsBoolean()) local.done(ints(b.getAsJsonArray("pool")));
                System.out.println("[mtga] FD premier guest: back at the table, seat " + local.seat + ", " + picks().size() + " picks");
            }
            case TableLink.BYE -> { }
            default -> System.out.println("[mtga] FD premier guest: unexpected frame " + type);
        }
    }

    private void send(int type, JsonObject body) {
        TableLink l = link;
        if (l != null && !l.isClosed()) l.trySend(type, 0, body);
    }

    private static List<Integer> ints(JsonArray a) {
        List<Integer> out = new ArrayList<>();
        if (a != null) for (JsonElement e : a) out.add(e.getAsInt());
        return out;
    }

    private static List<PublicProfile> players(JsonArray a) {
        List<PublicProfile> out = new ArrayList<>();
        for (JsonElement e : a) out.add(PublicProfile.fromJson(e.getAsJsonObject()));
        return out;
    }
}

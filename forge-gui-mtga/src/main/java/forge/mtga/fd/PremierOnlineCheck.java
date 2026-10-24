package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.model.FModel;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.HeadlessGui;
import forge.mtga.peer.PeerGuest;
import forge.mtga.profile.CosmeticCatalog;
import forge.mtga.profile.FileProfileStore;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static forge.mtga.fd.FrontDoor.obj;

/**
 * Headless check of an online premier table: three "servers" (each its own data folder, profile and {@link PremierDrafts})
 * meet in the coordinator's table queue (a local {@code npm run dev}, whose table deadlines are short), sit at one table
 * hosted by the first, draft it to the end and each get their own pool; one guest's server "restarts" in the middle and
 * rejoins its seat (picked for like a bot meanwhile). Then a second table where a guest never presses Ready: the table is
 * cancelled for both.
 * {@code java -Dmtga.premier.readySecs=4 forge.mtga.fd.PremierOnlineCheck <client folder> [ws://127.0.0.1:8787]};
 * prints PREMIER ONLINE CHECK OK / FAILED.
 */
public final class PremierOnlineCheck {
    private static final AtomicInteger failed = new AtomicInteger();

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failed.incrementAndGet();
    }

    /** One player's server, as far as premier drafts go, and its client's pushes. */
    private static final class Player {
        final String name;
        final File data;
        final CardDb db;
        final CosmeticCatalog catalog;
        final PremierDrafts.Online online;
        DraftEvents events;
        PremierDrafts premier;
        final BlockingQueue<JsonObject> pushes = new LinkedBlockingQueue<>();
        String draftId;
        int seat = -1, picks;
        JsonArray players;
        List<Integer> pool;

        Player(String name, CardDb db, CosmeticCatalog catalog, PremierDrafts.Online online) throws Exception {
            this.name = name;
            this.db = db;
            this.catalog = catalog;
            this.online = online;
            this.data = Files.createTempDirectory("premieronline-" + name).toFile();
            FileProfileStore store = new FileProfileStore(data);
            store.update(p -> p.displayName = name);
            boot();
        }

        /** (Re)starts this player's server over the same data folder. */
        void boot() {
            events = new DraftEvents(db, data);
            premier = new PremierDrafts(db, events, new ProfileApi(new FileProfileStore(data), catalog), () -> online);
        }

        void push(String json) { pushes.add(JsonParser.parseString(json).getAsJsonObject()); }

        /** The next DraftNotification of one of these types (pods and others skipped), or null. */
        JsonObject next(long ms, String... types) throws InterruptedException {
            long end = System.currentTimeMillis() + ms;
            for (;;) {
                JsonObject n = pushes.poll(Math.max(1, end - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                if (n == null) return null;
                JsonObject d = n.has("DraftNotification") ? n.getAsJsonObject("DraftNotification") : null;
                if (d == null) continue;
                for (String t : types) if (t.equals(d.get("Type").getAsString())) return d;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        if (System.getProperty("mtga.premier.botPace") == null) System.setProperty("mtga.premier.botPace", "100-250");
        forge.gui.GuiBase.setInterface(new HeadlessGui(new File(".")));
        FModel.initialize(null, null);
        File clientDir = new File(args.length > 0 ? args[0] : "../../client");
        String base = args.length > 1 ? args[1] : "ws://127.0.0.1:8787";
        File dbFile = CardDb.findDatabase(clientDir);
        CardDb db = new CardDb(dbFile);
        CosmeticCatalog catalog = CosmeticCatalog.load(clientDir);
        String code = "check" + Long.toString(System.currentTimeMillis(), 36);
        PremierDrafts.Online online = new PremierDrafts.Online(base, PeerGuest.fingerprint(dbFile), code);

        Player a = new Player("Alice", db, catalog, online), b = new Player("Bob", db, catalog, online), c = new Player("Carol", db, catalog, online);
        String event = PremierDrafts.PREFIX + PremierDrafts.CUBE_KEY;
        System.out.println(event + " at one table (Alice hosts, Carol's server restarts mid-draft):");
        List<Player> all = List.of(a, b, c);
        for (Player p : all) {
            p.premier.join(obj("EventName", event));
            p.premier.joinQueue(p::push, obj("EventName", event));
            Thread.sleep(300);                                  // arrival order = slot order: Alice hosts
        }
        for (Player p : all) {
            JsonObject req = p.next(20_000, "ReadyReq");
            p.draftId = req == null ? null : req.get("DraftId").getAsString();
        }
        check(all.stream().allMatch(p -> p.draftId != null), "all three seated: ready check on every client");
        check(a.premier.othersStillDrafting() == 2, "the host knows two others sit at its table");
        for (Player p : all) check(p.premier.ready(obj("EventName", event, "DraftId", p.draftId)) instanceof JsonObject, p.name + " ready");

        List<Thread> threads = new ArrayList<>();
        for (Player p : all) {
            Thread t = new Thread(() -> {
                try {
                    draft(p, p == c ? 8 : Integer.MAX_VALUE, event);
                    if (p == c) {
                        System.out.println("  .. Carol's server stops after " + p.picks + " picks");
                        PremierDrafts old = p.premier;           // the course stays as it was on disk
                        old.push.shutdownNow();
                        closeGuest(old);
                        Thread.sleep(1500);                       // the host picks for the seat meanwhile
                        p.pushes.clear();
                        p.boot();
                        Object r = p.premier.rejoin(p::push, obj("EventName", event));
                        check(r instanceof JsonObject, "Carol's restarted server rejoins the table");
                        draft(p, Integer.MAX_VALUE, event);
                    }
                } catch (Exception e) {
                    e.printStackTrace(System.out);
                    check(false, p.name + ": " + e);
                }
            }, "check-" + p.name);
            threads.add(t);
            t.start();
        }
        for (Thread t : threads) t.join(300_000);

        int[] seats = all.stream().mapToInt(p -> p.seat).toArray();
        check(seats[0] >= 0 && seats[0] != seats[1] && seats[1] != seats[2] && seats[0] != seats[2], "three different seats " + java.util.Arrays.toString(seats));
        check(a.players != null && a.players.get(b.seat).getAsJsonObject().get("ScreenName").getAsString().equals("Bob")
                && b.players.get(c.seat).getAsJsonObject().get("ScreenName").getAsString().equals("Carol"), "every table shows the others' names at their seats");
        for (Player p : all) check(p.pool != null && p.pool.size() == 45, p.name + "'s pool: " + (p.pool == null ? "none" : p.pool.size() + " cards"));
        check(c.picks < 45, "Carol made " + c.picks + " picks herself; her seat was picked for while she was away");
        Thread.sleep(3000);
        for (Player p : all) {
            JsonObject course = p.events.course(event);
            check(course.get("BotDecks") != null && course.getAsJsonArray("BotDecks").size() == 5, p.name + " has the 5 bot decks");
            check(!course.has("PremierSeat"), p.name + "'s course no longer keeps the seat");
        }
        check(a.premier.othersStillDrafting() == 0, "the host has nobody depending on it");

        System.out.println(event + " with a player who is never ready:");
        Player d = new Player("Dan", db, catalog, online), e = new Player("Eve", db, catalog, online);
        for (Player p : List.of(d, e)) {
            p.premier.join(obj("EventName", event));
            p.premier.joinQueue(p::push, obj("EventName", event));
            Thread.sleep(300);
        }
        for (Player p : List.of(d, e)) {
            JsonObject req = p.next(20_000, "ReadyReq");
            p.draftId = req == null ? null : req.get("DraftId").getAsString();
        }
        d.premier.ready(obj("EventName", event, "DraftId", d.draftId));
        JsonObject upd = e.next(5_000, "ReadyUpdate");
        upd = upd != null && upd.get("NumReady").getAsInt() == 7 ? upd : e.next(5_000, "ReadyUpdate");
        check(upd != null && upd.get("NumReady").getAsInt() == 7, "Eve sees 7 of 8 ready (6 bots + Dan)");
        long wait = (PremierDrafts.READY_SECONDS + 10) * 1000L;
        check(d.next(wait, "Close") != null && e.next(wait, "Close") != null, "nobody drafts: both are told the table closed");

        System.out.println(failed.get() == 0 ? "PREMIER ONLINE CHECK OK" : "PREMIER ONLINE CHECK FAILED (" + failed.get() + ")");
        System.exit(failed.get() == 0 ? 0 : 1);
    }

    /** As the client's draft scene: pick the first card of every pack until {@code stopAfter} picks or the last pick. */
    private static void draft(Player p, int stopAfter, String event) throws Exception {
        boolean completed = false;
        int mine = 0;
        while (!completed && mine < stopAfter) {
            JsonObject d = p.next(30_000, "TableInfo", "PickReq");
            if (d == null) {
                check(false, p.name + ": no pack for 30 s after " + p.picks + " picks");
                return;
            }
            if (d.get("Type").getAsString().equals("TableInfo")) {
                JsonObject t = d.getAsJsonObject("TableInfo");
                p.seat = t.get("SelfSeat").getAsInt();
                p.players = t.getAsJsonArray("Players");
            }
            JsonObject pi = d.getAsJsonObject("PickInfo");
            JsonArray cards = pi.getAsJsonArray("PackCards");
            if (cards.isEmpty()) continue;
            JsonObject r = (JsonObject) p.premier.pick(obj("DraftId", p.draftId, "GrpIds", one(cards.get(0).getAsInt()),
                    "Pack", pi.get("SelfPack").getAsInt(), "Pick", pi.get("SelfPick").getAsInt()));
            if (!r.get("IsPickSuccessful").getAsBoolean()) continue;     // the timer took it meanwhile: the next push follows
            p.picks++;
            mine++;
            completed = r.get("IsPickingCompleted").getAsBoolean();
        }
        if (!completed) return;
        JsonObject course = (JsonObject) p.premier.complete(obj("EventName", event, "IsBotDraft", false));
        List<Integer> pool = new ArrayList<>();
        course.getAsJsonArray("CardPool").forEach(x -> pool.add(x.getAsInt()));
        p.pool = pool;
    }

    /** The guest link of a stopped server goes down the way a killed process's would (no goodbye to the host). */
    private static void closeGuest(PremierDrafts old) throws Exception {
        java.lang.reflect.Field f = PremierDrafts.class.getDeclaredField("local");
        f.setAccessible(true);
        PremierDrafts.Local l = (PremierDrafts.Local) f.get(old);
        if (l != null && l.guest != null) l.guest.close();
        f.set(old, null);
    }

    private static JsonArray one(int grp) {
        JsonArray a = new JsonArray();
        a.add(grp);
        return a;
    }
}

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
 * Phase 1: the table runs in this server with the player and seven Forge bots ({@link DraftTable}); seats of other
 * players come later through the same {@link DraftTable.Seat} interface.
 */
final class PremierDrafts {
    static final String PREFIX = "PremierDraft_";
    static final String CUBE_KEY = "VCUBE";
    /** Seconds the player has to press Ready once the table is full. */
    private static final int READY_SECONDS = 30;
    private static final int POD = 8;

    /** One premier event: a set (by code) or a cube (by draft file name). */
    record Spec(String key, String setCode, String cubeFile, String title, int collation, List<Integer> poolGrpIds) {
        String eventName() { return PREFIX + key; }

        boolean isCube() { return cubeFile != null; }
    }

    private final CardDb db;
    private final DraftEvents events;
    private final ProfileApi profile;
    private final Random rnd = new Random();
    /** Pushes go out on one thread, a moment after the reply of the request that caused them (the client handles a pick's reply first). */
    private final ScheduledExecutorService push = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "fd-draft-push");
        t.setDaemon(true);
        return t;
    });
    private List<Spec> specs;
    /** The table being formed or drafted, at most one (one local player). */
    private volatile Local local;

    PremierDrafts(CardDb db, DraftEvents events, ProfileApi profile) {
        this.db = db;
        this.events = events;
        this.profile = profile;
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

    /** EventJoinDraftQueue: look for a table. Phase 1: the other seven seats fill with bots at once, then the ready check. */
    Object joinQueue(java.util.function.Consumer<String> pushTo, JsonObject req) {
        String name = str(req, "EventName");
        Spec spec = spec(name);
        if (spec == null) return new FrontDoor.Err(7000, "unknown event " + name);
        Local old = local;
        if (old != null) old.close();
        Local l = new Local(pushTo, spec);
        local = l;
        System.out.println("[mtga] FD premier " + name + ": looking for a table");
        for (int n = 1; n <= POD; n++) {
            int filled = n;
            push.schedule(() -> l.pod(filled), 300L + 250L * n, TimeUnit.MILLISECONDS);
        }
        push.schedule(l::readyCheck, 300L + 250L * POD + 400, TimeUnit.MILLISECONDS);
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

    /** EventPlayerDraftReadyPlayer{EventName, DraftId}: the player is ready; the bots are too, so the draft starts. */
    Object ready(JsonObject req) {
        String name = str(req, "EventName");
        Local l = local;
        if (l == null || !l.spec.eventName().equals(name) || !l.draftId.equals(str(req, "DraftId"))) return new FrontDoor.Err(7000, "no table for " + name);
        JsonObject c = events.course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        String problem = l.start();
        if (problem != null) return new FrontDoor.Err(7000, problem);
        c.addProperty("DraftId", l.draftId);
        events.save(c);
        return c;
    }

    /** EventJoinDraft (rejoin after the client restarted): the course, and the table state pushed again. */
    Object rejoin(JsonObject req) {
        String name = str(req, "EventName");
        JsonObject c = events.course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        Local l = local;
        if (l == null || l.table == null || !l.spec.eventName().equals(name)) {
            c.add("DraftId", null);           // the table is gone (server restarted): back to looking for one
            events.save(c);
            return c;
        }
        push.schedule(l::resend, 500, TimeUnit.MILLISECONDS);
        return c;
    }

    /** EventPlayerDraftMakePick{DraftId, GrpIds, Pack, Pick}. */
    Object pick(JsonObject req) {
        Local l = local;
        if (l == null || l.table == null || !l.draftId.equals(str(req, "DraftId"))) return new FrontDoor.Err(7000, "no draft " + str(req, "DraftId"));
        JsonElement ids = req.get("GrpIds");
        int grp = ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() > 0 ? ids.getAsJsonArray().get(0).getAsInt() : 0;
        PaperCard card = l.offered(grp);
        boolean ok = card != null && l.table.pick(l.seat, card);
        if (!ok) {
            System.out.println("[mtga] FD premier: pick " + grp + " refused");
            return obj("IsPickingCompleted", false, "IsPickSuccessful", false, "TableInfo", l.tableInfo(), "PickInfo", l.pickInfo(), "PackInfo", l.packInfo());
        }
        return obj("IsPickingCompleted", l.done, "IsPickSuccessful", true, "TableInfo", null, "PickInfo", null, "PackInfo", null);
    }

    /** EventPlayerDraftReserveCard / EventPlayerDraftClearReservedCard: the card the pick timer takes. */
    Object reserve(JsonObject req, boolean clear) {
        Local l = local;
        if (l == null || l.table == null) return new FrontDoor.Err(7000, "no draft");
        PaperCard card = null;
        if (!clear) {
            JsonElement ids = req.get("GrpIds");
            int grp = ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() > 0 ? ids.getAsJsonArray().get(0).getAsInt() : 0;
            card = l.offered(grp);
        }
        l.table.reserve(l.seat, card);
        return obj("IsPickingCompleted", false, "IsPickSuccessful", true, "TableInfo", null, "PickInfo", null, "PackInfo", null);
    }

    /** EventPlayerDraftGetTablePacks{DraftId}: packs waiting per seat (booster art per pack). */
    Object tablePacks(JsonObject req) {
        Local l = local;
        if (l == null || l.table == null) return new FrontDoor.Err(7000, "no draft");
        JsonArray bySeat = new JsonArray();
        for (int q : l.table.queueSizes()) bySeat.add(l.packs(q));
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
            for (PaperCard p : l.pool) {
                int g = db.grpId(p.getName());
                if (g != 0) pool.add(g);
            }
            c.add("CardPool", pool);
            c.addProperty("CurrentModule", "DeckSelect");
            c.add("DraftId", null);
            if (l.table.isOver()) c.add("BotDecks", DraftEvents.decksJson(l.table.botDecks(), db));    // else saved when the table ends
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

    /** EventDrop / EventResign of a premier event: close its table too. */
    void dropped(String eventName) {
        Local l = local;
        if (l != null && l.spec.eventName().equals(eventName)) {
            l.close();
            local = null;
        }
    }

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

    private static JsonObject module() {
        return obj("CurrentModule", "PlayerDraft", "Payload", "", "DTO_InventoryInfo", null);
    }

    // ---- the local player's seat ----------------------------------------------------------------------------------

    /** The local player's table: queue, ready check, then the seat at a {@link DraftTable}. */
    private final class Local implements DraftTable.Seat {
        final java.util.function.Consumer<String> session;
        final Spec spec;
        final String draftId = java.util.UUID.randomUUID().toString();
        final int seat = rnd.nextInt(POD);
        final List<PublicProfile> players = new ArrayList<>();
        volatile DraftTable table;
        volatile boolean ready, done, tableSent, closed;
        volatile List<PaperCard> pool;
        /** The pack offered for the pending pick, and its numbers. */
        volatile List<PaperCard> offer = List.of();
        volatile int packNo = 1, pickNo = 1, seconds;
        private int[] lastQueues;

        Local(java.util.function.Consumer<String> session, Spec spec) {
            this.session = session;
            this.spec = spec;
            for (int i = 0; i < POD; i++) players.add(i == seat ? profile.self() : profile.forAi("Forge Bot " + (i < seat ? i + 1 : i)));
        }

        void pod(int filled) {
            if (closed || table != null) return;
            JsonArray details = new JsonArray();
            send(obj("Type", "PodNotification", "PodQueueNotification", obj("EventId", spec.eventName(), "NumInPod", filled,
                    "PodCapacity", POD, "PlayerDetails", details)));
        }

        void readyCheck() {
            if (closed || table != null) return;
            draft("ReadyReq", obj("TimeoutSec", READY_SECONDS, "NumReady", POD - 1));
            draft("ReadyUpdate", obj("NumReady", POD - 1));          // the bots are ready at once
            push.schedule(() -> {
                if (!ready && !closed && local == this) {
                    System.out.println("[mtga] FD premier " + spec.eventName() + ": not ready in time, table closed");
                    session.accept(notification("Close", obj()).toString());
                    close();
                    local = null;
                }
            }, READY_SECONDS + 2, TimeUnit.SECONDS);
        }

        /** Ready pressed: build the table and deal (the first pack goes out with the TableInfo push). */
        String start() {
            if (table != null) return null;
            ready = true;
            TableDraft d;
            try {
                d = spec.isCube() ? TableDraft.forCube(spec.cubeFile(), c -> db.grpId(c.getName()) != 0)
                        : TableDraft.forSet(StaticData.instance().getEditions().get(spec.setCode()));
            } catch (Exception e) {
                System.out.println("[mtga] FD premier " + spec.eventName() + ": cannot open the packs: " + e);
                e.printStackTrace(System.out);
                return "cannot open the packs for " + spec.title();
            }
            draft("ReadyUpdate", obj("NumReady", POD));
            table = new DraftTable(d, Map.of(seat, this), c -> db.grpId(c.getName()) != 0);
            int[] pace = botPace();
            table.setBotPace(pace[0], pace[1]);
            System.out.println("[mtga] FD premier " + spec.eventName() + ": table " + table.id() + ", seat " + seat + " of " + table.podSize());
            push.schedule(table::start, 600, TimeUnit.MILLISECONDS);
            return null;
        }

        PaperCard offered(int grpId) {
            for (PaperCard c : offer) if (db.grpId(c.getName()) == grpId) return c;
            return null;
        }

        @Override
        public void pack(DraftTable t, List<PaperCard> cards, int packNo, int pickNo, int seconds) {
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

        @Override
        public void autoPicked(DraftTable t, PaperCard card) {
            System.out.println("[mtga] FD premier: picked for the player: " + card.getName());
        }

        @Override
        public void moved(DraftTable t) {
            if (!tableSent) return;
            int[] q = t.queueSizes();
            int self = q[seat], neg = q[Math.floorMod(seat - 1, POD)], pos = q[Math.floorMod(seat + 1, POD)];
            int[] now = {self, neg, pos};
            if (Arrays.equals(now, lastQueues)) return;
            lastQueues = now;
            draft("PackInfo", obj("PackInfo", packInfo()));
        }

        @Override
        public void done(DraftTable t, List<PaperCard> pool) {
            this.pool = pool;
            this.done = true;
            this.offer = List.of();
            System.out.println("[mtga] FD premier " + spec.eventName() + ": last pick made (" + pool.size() + " cards)");
        }

        /** The bots finished after the player: keep their decks as the offline opponents. */
        @Override
        public void tableOver(DraftTable t) {
            JsonObject c = events.course(spec.eventName());
            if (c == null || c.has("BotDecks") && c.get("BotDecks").isJsonArray()) return;
            c.add("BotDecks", DraftEvents.decksJson(t.botDecks(), db));
            events.save(c);
        }

        /** The whole state again (rejoin). */
        void resend() {
            DraftTable t = table;
            if (t == null) return;
            draft("TableInfo", obj("TableInfo", tableInfo(), "PickInfo", pickInfo(), "PackInfo", packInfo()));
        }

        JsonObject tableInfo() {
            JsonArray ps = new JsonArray();
            for (PublicProfile p : players) ps.add(obj("ScreenName", p.name(), "Avatar", p.avatar().isEmpty() ? "UNKNOWN" : p.avatar()));
            JsonArray picked = new JsonArray(), styles = new JsonArray();
            DraftTable t = table;
            if (t != null) {
                for (PaperCard c : t.picks(seat)) {
                    int g = db.grpId(c.getName());
                    if (g != 0) picked.add(g);
                }
            }
            return obj("Players", ps, "SelfSeat", seat, "SelfPack", packNo, "PickedCards", picked, "PickedStyles", styles);
        }

        JsonObject pickInfo() {
            JsonArray cards = new JsonArray(), styles = new JsonArray();
            for (PaperCard c : offer) cards.add(db.grpId(c.getName()));
            DraftTable t = table;
            boolean up = t == null || t.passesUp();
            return obj("PackCards", cards, "NumCardsToPick", 1, "SuggestedCards", new JsonArray(), "TimeoutSec", seconds,
                    "PassDirection", up ? "POS" : "NEG", "SelfPack", packNo, "SelfPick", pickNo, "PackStyles", styles);
        }

        JsonObject packInfo() {
            DraftTable t = table;
            int[] q = t == null ? new int[POD] : t.queueSizes();
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

        void close() {
            closed = true;
            DraftTable t = table;
            if (t != null && !t.isOver()) t.close();
        }
    }
}

package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.StaticData;
import forge.card.CardEdition;
import forge.deck.CardPool;
import forge.deck.Deck;
import forge.gamemodes.limited.BoosterDraft;
import forge.gamemodes.limited.DraftPack;
import forge.gamemodes.limited.LimitedPoolType;
import forge.item.PaperCard;
import forge.model.CardBlock;
import forge.mtga.forge.CardDb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * Quick (bot) drafts as client events: one event {@code QuickDraft_<SET>} per Forge set whose cards the client mostly
 * has. The client's own bot-draft flow does the UI (BotDraftPod: BotDraftStartDraft / DraftStatus / DraftPick, then the
 * deck builder over the course's CardPool); this class is the server side, with Forge's {@link BoosterDraft} running
 * the pod (seat 0 = the player, 7 {@code LimitedPlayerAI} bots). Course per event (ClientPlayerCourseV3 JSON) lives in
 * {@code <dataDir>/events/<event>.json}; a draft in progress is held in memory only (restarting the server restarts it).
 * <p>
 * Course modules used: (none = Join) -> BotDraft -> DeckSelect (pool granted) -> CreateMatch (deck submitted).
 */
final class DraftEvents {
    static final String PREFIX = "QuickDraft_";
    private static final int MATCH_DECK_MIN = 40;
    /** Arena's quick draft: best-of-1 matches until 7 wins or 3 losses. */
    static final int MAX_WINS = 7, MAX_LOSSES = 3;

    /** A draftable Forge set. */
    record SetInfo(String code, String name, java.util.Date date, int cards, int mapped, List<Integer> grpIds) { }

    private final CardDb db;
    private final File dir;
    private final Map<String, JsonObject> courses = new LinkedHashMap<>();
    private final Map<String, Draft> drafts = new HashMap<>();
    private final Random rnd = new Random();
    private List<SetInfo> catalog;

    DraftEvents(CardDb db, File dataDir) {
        this.db = db;
        this.dir = new File(dataDir, "events");
        dir.mkdirs();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try {
                    JsonObject c = JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                    courses.put(str(c, "InternalEventName"), c);
                } catch (Exception e) {
                    System.out.println("[mtga] FD: unreadable event file " + f + ": " + e);
                }
            }
        }
    }

    static boolean isDraftEvent(String eventName) { return eventName != null && eventName.startsWith(PREFIX); }

    // ---- catalog ------------------------------------------------------------------------------------------------

    /**
     * Sets worth offering: core/expansion/draft-type editions with a Forge draft booster template where at least
     * -Dmtga.draft.minCoverage (default 0.9) of the set's cards exist in the client's card database, newest first,
     * at most -Dmtga.draft.maxSets (default 80).
     */
    synchronized List<SetInfo> catalog() {
        if (catalog != null) return catalog;
        double min = Double.parseDouble(System.getProperty("mtga.draft.minCoverage", "0.9"));
        int max = Integer.getInteger("mtga.draft.maxSets", 80);
        List<SetInfo> out = new ArrayList<>();
        for (CardEdition e : StaticData.instance().getEditions()) {
            CardEdition.Type t = e.getType();
            if (t != CardEdition.Type.CORE && t != CardEdition.Type.EXPANSION && t != CardEdition.Type.DRAFT) continue;
            if (!e.hasBoosterTemplate() || e.getDate() == null) continue;
            int cards = 0, mapped = 0;
            java.util.Set<Integer> grps = new java.util.LinkedHashSet<>();
            for (CardEdition.EditionEntry c : e.getAllCardsInSet()) {
                if (c.rarity() == forge.card.CardRarity.BasicLand) continue;
                cards++;
                int g = db.grpId(c.name());
                if (g != 0) { mapped++; grps.add(g); }
            }
            if (cards >= 60 && mapped >= cards * min) out.add(new SetInfo(e.getCode(), e.getName(), e.getDate(), cards, mapped, new ArrayList<>(grps)));
        }
        out.sort(Comparator.comparing(SetInfo::date).reversed());
        if (out.size() > max) out = new ArrayList<>(out.subList(0, max));
        catalog = out;
        System.out.println("[mtga] FD quick draft sets: " + out.size() + " (" + out.stream().map(SetInfo::code).toList() + ")");
        return catalog;
    }

    private SetInfo set(String eventName) {
        String code = eventName.substring(PREFIX.length());
        for (SetInfo s : catalog()) if (s.code().equalsIgnoreCase(code)) return s;
        return null;
    }

    /** EventInfoV3 entries for EventGetActiveEventsV2.Events. */
    JsonArray eventInfos() {
        JsonArray out = new JsonArray();
        int priority = 10;   // the client lists ascending: newest set (first in the catalog) gets the lowest value
        for (SetInfo s : catalog()) {
            String name = PREFIX + s.code();
            int coll = CollationIds.of(s.code());
            JsonArray collations = new JsonArray();
            for (int i = 0; i < 3; i++) collations.add(coll);
            JsonObject data = obj(
                    "BoosterPacksDisplay", obj("CardGrantTime", "PreMatch", "CollationIds", collations),
                    "DraftScreenUI", obj("TimeoutWarnSec", 0, "TimeoutDiscoSec", 0),
                    "TitleRankText", obj("LocKey", "Events/Event_Title_" + name),
                    "DescriptionText", obj("LocKey", "Events/Event_Desc_" + name),
                    "ResignWidget", obj(),
                    "LossDetailsDisplay", obj("Games", MAX_LOSSES, "LossDetailsType", "LossSlots"),
                    "MainButtonWidget", obj(),
                    "SelectedDeckWidget", obj("DeckButtonBehavior", "Editable", "ShowCopyDeckButton", false),
                    "ViewCardPoolWidget", obj("CardPool", grpArray(s.grpIds())));
            JsonObject ux = obj("PublicEventName", name, "DisplayPriority", priority++, "PrioritizeBannerIfPlayerHasToken", false,
                    "Parameters", obj(), "Group", "", "EventBladeBehavior", "EventPage", "DynamicFilterTagIds", new JsonArray(),
                    "DeckSelectFormat", ForgeFormats.LIMITED, "FactionSealedUXInfo", new JsonArray(), "Prizes", obj(),
                    "EventComponentData", data, "Joinability", "Open");
            out.add(obj("InternalEventName", name, "EventState", "Active", "FormatType", "Draft",
                    "StartTime", "2020-01-01T00:00:00Z", "LockedTime", "2099-01-01T00:00:00Z", "ClosedTime", "2099-01-01T00:00:00Z",
                    "Flags", new JsonArray(), "PastEntries", obj(), "EntryFees", new JsonArray(), "EventTags", new JsonArray(),
                    "EventUXInfo", ux, "WinCondition", "SingleElimination"));
        }
        return out;
    }

    private static JsonArray grpArray(List<Integer> ids) {
        JsonArray a = new JsonArray();
        for (int g : ids) a.add(g);
        return a;
    }

    // ---- courses ------------------------------------------------------------------------------------------------

    JsonArray courseList() {
        JsonArray out = new JsonArray();
        synchronized (courses) { for (JsonObject c : courses.values()) out.add(c); }
        return out;
    }

    private JsonObject newCourse(String eventName, String module) {
        return obj("CourseId", UUID.randomUUID().toString(), "InternalEventName", eventName, "CurrentModule", module,
                "ModulePayload", "", "CourseDeckSummary", emptySummary(), "CourseDeck", emptyDeck(), "CurrentWins", 0, "CurrentLosses", 0,
                "CardPool", new JsonArray(), "CardPoolByCollation", null, "CardStyles", new JsonArray(),
                "DraftId", null, "TournamentId", null, "MadeChoice", null);
    }

    /** A summary/deck the client can convert before the player has built one (null lists break its course update). */
    private static JsonObject emptySummary() {
        return obj("DeckId", UUID.randomUUID().toString(), "Mana", "", "Name", "Draft Deck",
                "Attributes", attrs("Format", ForgeFormats.LIMITED), "Description", "", "DeckTileId", 0, "DeckArtId", 0,
                "IsCompanionValid", false, "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
    }

    private static JsonObject emptyDeck() {
        return obj("MainDeck", new JsonArray(), "Sideboard", new JsonArray(), "CommandZone", new JsonArray(), "Companions", new JsonArray(),
                "CardSkins", new JsonArray());
    }

    private static JsonArray attrs(String name, String value) {
        JsonArray a = new JsonArray();
        a.add(obj("name", name, "value", value));
        return a;
    }

    private void save(JsonObject course) {
        String name = str(course, "InternalEventName");
        synchronized (courses) {
            courses.put(name, course);
            try {
                Files.writeString(new File(dir, name + ".json").toPath(), course.toString(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                System.out.println("[mtga] FD: cannot write event course " + name + ": " + e);
            }
        }
    }

    private void remove(String eventName) {
        synchronized (courses) {
            courses.remove(eventName);
            new File(dir, eventName + ".json").delete();
        }
        drafts.remove(eventName);
    }

    JsonObject course(String eventName) { synchronized (courses) { return courses.get(eventName); } }

    // ---- handlers -----------------------------------------------------------------------------------------------

    /** EventJoin: enter the event (free), landing in the bot draft. */
    Object join(JsonObject req) {
        String name = str(req, "EventName");
        if (!isDraftEvent(name) || set(name) == null) return new FrontDoor.Err(7000, "unknown event " + name);
        JsonObject c = course(name);
        if (c == null) { c = newCourse(name, "BotDraft"); save(c); }
        return obj("Course", c, "InventoryInfo", null);
    }

    /** BotDraft_StartDraft: build the pod (resuming a draft already in memory). */
    Object startDraft(JsonObject req) {
        String name = str(req, "EventName");
        SetInfo set = isDraftEvent(name) ? set(name) : null;
        if (set == null) return moduleReply(name, "Error_DraftDefinitionNotFound");
        synchronized (this) {
            if (!drafts.containsKey(name)) {
                Draft d = Draft.create(set, db);
                if (d == null) return moduleReply(name, "Error_GeneratingPacks");
                drafts.put(name, d);
                System.out.println("[mtga] FD quick draft " + set.code() + " (" + set.name() + ") started");
            }
        }
        return obj("CurrentModule", "BotDraft", "Payload", "", "DTO_InventoryInfo", null);
    }

    Object draftStatus(JsonObject req) {
        String name = str(req, "EventName");
        Draft d = drafts.get(name);
        if (d == null) {
            // finished draft (pool granted): report it as completed
            JsonObject c = course(name);
            if (c != null && "DeckSelect".equals(str(c, "CurrentModule")) || c != null && "CreateMatch".equals(str(c, "CurrentModule"))) {
                JsonArray picked = new JsonArray();
                for (JsonElement g : c.getAsJsonArray("CardPool")) picked.add(g.getAsString());
                JsonObject payload = obj("Result", "Success", "EventName", name, "DraftStatus", "Completed", "PackNumber", 2, "PickNumber", 0,
                        "NumCardsToPick", 1, "DraftPack", new JsonArray(), "PackStyles", new JsonArray(), "PickedCards", picked,
                        "PickedStyles", new JsonArray());
                return obj("CurrentModule", "DeckSelect", "Payload", payload.toString(), "DTO_InventoryInfo", null);
            }
            return moduleReply(name, "Error_DraftNotFound");
        }
        return draftReply(name, d, "Success");
    }

    Object draftPick(JsonObject req) {
        String name = str(req, "EventName");
        Draft d = drafts.get(name);
        if (d == null) return moduleReply(name, "Error_DraftNotFound");
        JsonObject info = get(req, "PickInfo") != null && get(req, "PickInfo").isJsonObject() ? get(req, "PickInfo").getAsJsonObject() : req;
        JsonElement ids = get(info, "CardIds");
        int grp = ids != null && ids.isJsonArray() && ids.getAsJsonArray().size() > 0 ? Integer.parseInt(ids.getAsJsonArray().get(0).getAsString()) : 0;
        if (!d.pick(grp)) return draftReply(name, d, "Error_BadPick");
        if (d.done()) completeDraft(name, d);
        return draftReply(name, d, "Success");
    }

    private Object moduleReply(String name, String result) {
        JsonObject payload = obj("Result", result, "EventName", name, "DraftStatus", "None", "PackNumber", 0, "PickNumber", 0,
                "NumCardsToPick", 1, "DraftPack", new JsonArray(), "PackStyles", new JsonArray(), "PickedCards", new JsonArray(),
                "PickedStyles", new JsonArray());
        return obj("CurrentModule", "BotDraft", "Payload", payload.toString(), "DTO_InventoryInfo", null);
    }

    private Object draftReply(String name, Draft d, String result) {
        JsonArray pack = new JsonArray(), picked = new JsonArray();
        if (!d.done()) for (int g : d.visiblePack()) pack.add(String.valueOf(g));
        for (int g : d.pickedGrpIds()) picked.add(String.valueOf(g));
        JsonObject payload = obj("Result", result, "EventName", name, "DraftStatus", d.done() ? "Completed" : "PickNext",
                "PackNumber", d.packNumber(), "PickNumber", d.pickNumber(), "NumCardsToPick", 1, "DraftPack", pack,
                "PackStyles", new JsonArray(), "PickedCards", picked, "PickedStyles", new JsonArray());
        return obj("CurrentModule", d.done() ? "DeckSelect" : "BotDraft", "Payload", payload.toString(), "DTO_InventoryInfo", null);
    }

    /** The draft is over: grant the pool (the client then asks for the courses) and keep the bots' decks as opponents. */
    private void completeDraft(String name, Draft d) {
        JsonObject c = course(name);
        if (c == null) c = newCourse(name, "DeckSelect");
        c.addProperty("CurrentModule", "DeckSelect");
        JsonArray pool = new JsonArray();
        for (int g : d.pickedGrpIds()) pool.add(g);
        c.add("CardPool", pool);
        c.add("BotDecks", d.botDecks());
        save(c);
        drafts.remove(name);
        System.out.println("[mtga] FD quick draft " + name + " done: " + pool.size() + " cards in the pool");
    }

    /** EventSetDeckV3: the deck for the event. */
    Object setDeck(JsonObject req) {
        String name = str(req, "EventName");
        JsonObject c = course(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        JsonElement summary = get(req, "Summary"), deck = get(req, "Deck");
        if (summary != null) c.add("CourseDeckSummary", summary);
        if (deck != null) c.add("CourseDeck", deck);
        c.addProperty("CurrentModule", "CreateMatch");
        save(c);
        return c;
    }

    /** EventDrop / EventResign: leave the event, back to Join. */
    Object drop(JsonObject req, boolean wrapped) {
        String name = str(req, "EventName");
        remove(name);
        JsonObject join = newCourse(name, "Join");
        return wrapped ? obj("Course", join, "InventoryInfo", null) : join;
    }

    /** The opponent of the next match: the next bot of the pod (a different one each round), as name -> quantity; null if none is stored. */
    Map<String, Integer> botDeck(String eventName) {
        JsonObject c = course(eventName);
        JsonElement bd = c == null ? null : c.get("BotDecks");
        if (bd == null || !bd.isJsonArray() || bd.getAsJsonArray().size() == 0) return null;
        JsonArray decks = bd.getAsJsonArray();
        int games = c.get("CurrentWins").getAsInt() + c.get("CurrentLosses").getAsInt();
        JsonObject pick = decks.get(games % decks.size()).getAsJsonObject();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : pick.entrySet()) out.put(e.getKey(), e.getValue().getAsInt());
        return out;
    }

    /** A finished event match: count it; 7 wins or 3 losses end the event (module Complete). */
    synchronized void recordResult(String eventName, boolean won) {
        JsonObject c = course(eventName);
        if (c == null) return;
        String key = won ? "CurrentWins" : "CurrentLosses";
        c.addProperty(key, c.get(key).getAsInt() + 1);
        int w = c.get("CurrentWins").getAsInt(), l = c.get("CurrentLosses").getAsInt();
        if (w >= MAX_WINS || l >= MAX_LOSSES) c.addProperty("CurrentModule", "Complete");
        save(c);
        System.out.println("[mtga] FD " + eventName + ": " + (won ? "won" : "lost") + " -> " + w + "-" + l
                + (w >= MAX_WINS || l >= MAX_LOSSES ? " (event over)" : ""));
    }

    /** CurrentModule of an event's course, "Complete" if it has none. */
    String module(String eventName) {
        JsonObject c = course(eventName);
        return c == null ? "Complete" : str(c, "CurrentModule");
    }

    private static JsonElement get(JsonObject o, String key) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }

    // ---- one draft pod ------------------------------------------------------------------------------------------

    /** Forge pod: seat 0 is the player, the other seven are AI. Only cards with a client GrpId are shown or kept. */
    static final class Draft {
        private final BoosterDraft pod;
        private final CardDb db;
        private final List<PaperCard> picks = new ArrayList<>();
        private DraftPack pack;
        private int round = -1, pick;
        private boolean done;

        private Draft(BoosterDraft pod, CardDb db) {
            this.pod = pod;
            this.db = db;
        }

        static Draft create(SetInfo set, CardDb db) {
            try {
                CardEdition ed = StaticData.instance().getEditions().get(set.code());
                CardBlock block = new CardBlock(0, set.name(), List.of(ed), List.of(), ed, 3, 6);
                String[] boosters = {set.code(), set.code(), set.code()};
                BoosterDraft pod = BoosterDraft.createDraft(LimitedPoolType.Block, block, boosters, BoosterDraft.N_PLAYERS);
                if (pod == null) return null;
                Draft d = new Draft(pod, db);
                d.advance();
                return d;
            } catch (Exception e) {
                System.out.println("[mtga] FD quick draft " + set.code() + " failed: " + e);
                e.printStackTrace(System.out);
                return null;
            }
        }

        /** Move to the next pack the player must pick from; packs without a client card are auto-picked. */
        private void advance() {
            while (true) {
                CardPool next = pod.hasNextChoice() ? pod.nextChoice() : null;
                if (next == null) { done = true; pack = null; return; }
                pack = pod.getHumanPlayer().nextChoice();
                if (pod.getRound() != round) { round = pod.getRound(); pick = 0; }
                if (!visible(pack).isEmpty()) return;
                PaperCard any = pack.get(0);
                picks.add(any);
                pod.setChoice(any, forge.deck.DeckSection.Main);
                pick++;
            }
        }

        private List<PaperCard> visible(DraftPack p) {
            List<PaperCard> out = new ArrayList<>();
            for (PaperCard c : p) if (db.grpId(c.getName()) != 0) out.add(c);
            return out;
        }

        boolean done() { return done; }

        int packNumber() { return Math.max(0, round - 1); }

        int pickNumber() { return pick; }

        List<Integer> visiblePack() {
            List<Integer> out = new ArrayList<>();
            if (pack != null) for (PaperCard c : visible(pack)) out.add(db.grpId(c.getName()));
            return out;
        }

        List<Integer> pickedGrpIds() {
            List<Integer> out = new ArrayList<>();
            for (PaperCard c : picks) {
                int g = db.grpId(c.getName());
                if (g != 0) out.add(g);
            }
            return out;
        }

        boolean pick(int grpId) {
            if (done || pack == null) return false;
            PaperCard chosen = null;
            for (PaperCard c : visible(pack)) if (db.grpId(c.getName()) == grpId) { chosen = c; break; }
            if (chosen == null) return false;
            picks.add(chosen);
            pod.setChoice(chosen, forge.deck.DeckSection.Main);
            pick++;
            advance();
            return true;
        }

        /** The seven bots' built decks as [{cardName: quantity}]. */
        JsonArray botDecks() {
            JsonArray out = new JsonArray();
            try {
                for (Deck d : pod.getComputerDecks()) {
                    JsonObject o = new JsonObject();
                    int total = 0;
                    for (Map.Entry<PaperCard, Integer> e : d.getMain()) {
                        if (db.grpId(e.getKey().getName()) == 0) continue;
                        o.addProperty(e.getKey().getName(), e.getValue());
                        total += e.getValue();
                    }
                    if (total >= MATCH_DECK_MIN) out.add(o);
                }
            } catch (Exception e) {
                System.out.println("[mtga] FD quick draft: cannot build the bots' decks: " + e);
            }
            return out;
        }
    }
}

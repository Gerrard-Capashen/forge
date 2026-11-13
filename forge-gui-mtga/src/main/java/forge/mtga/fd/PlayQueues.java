package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static forge.mtga.fd.FrontDoor.obj;

/**
 * The play blade's "Find Match" queues: Bot Play (against the Forge AI) and Casual Play (against another player), each in
 * Standard, Pioneer, Modern, Legacy, Vintage, Pauper and Premodern. Every queue is an ordinary constructed event named {@code <Mode>_<Format>} whose deck format is
 * the queue's format, so the client's deck picker only offers decks that are valid there; the server checks the submitted deck
 * again ({@link ForgeFormats#problem}). They are not "AIBotMatch" events, so the client runs the normal ladder flow
 * (EventJoin, EventSetDeckV3, EventEnterPairing) and the server starts the match on EventEnterPairing.
 * Casual Play and Ranked pair two players through the coordinator, which keys its queues on the event name. Ranked events carry the
 * "Ranked" flag (the client animates pips on the end screen only for those) and sit in the blade's Ranked list; every ranked format
 * moves the same Constructed rank (the client shows one), see coordinator/src/rank.ts.
 */
final class PlayQueues {
    enum Mode {
        BOT("BotPlay", "bot-", "Unranked", "Bot Play"), CASUAL("CasualPlay", "casual-", "Unranked", "Casual Play"),
        RANKED("RankedPlay", "ranked-", "Ranked", "Ranked"),
        /** Momir Basic: casual only (Mastery XP and quests, no rank), against another player or, when nobody queues, the AI. */
        MOMIR("MomirPlay", "momir-", "Unranked", "Momir Basic");
        final String prefix, idPrefix, queueType, label;
        Mode(String prefix, String idPrefix, String queueType, String label) {
            this.prefix = prefix;
            this.idPrefix = idPrefix;
            this.queueType = queueType;
            this.label = label;
        }
    }

    /** Formats offered by every mode (client format names, see {@link ForgeFormats#SPECS}). */
    static final List<String> FORMATS = List.of("Standard", "Pioneer", "Modern", "Legacy", "Vintage", "Pauper", "Premodern");

    /**
     * Brawl: Bot Play and Casual Play only (no Brawl ladder), best of one (the sideboard screen has no command zone). Its queues sit under the
     * play blade's own Brawl tab ({@code QueueType} "Brawl"), which the client keeps locked while that list is empty.
     */
    static final String BRAWL = ForgeFormats.BRAWL;

    /** The formats a mode offers: the constructed ones, plus Brawl for the unranked modes. */
    static List<String> formats(Mode m) {
        if (m == Mode.MOMIR) return List.of(ForgeFormats.MOMIR);
        if (m == Mode.RANKED) return FORMATS;
        List<String> all = new java.util.ArrayList<>(FORMATS);
        all.add(BRAWL);
        return all;
    }

    private static String queueType(Mode m, String format) { return BRAWL.equals(format) ? "Brawl" : m.queueType; }

    /** Bot Play and Ranked offer a best-of-three event next to the best-of-one one (not Brawl, not Casual Play or Momir yet). */
    private static boolean hasBo3(Mode m, String format) { return (m == Mode.BOT || m == Mode.RANKED) && !BRAWL.equals(format); }

    /** Play-blade queue id of a mode and format, e.g. "bot-standard". */
    static String queueId(Mode mode, String format) { return mode.idPrefix + format.toLowerCase(); }

    /** Ranked queues: online only, the match moves the player's Constructed rank. */
    static boolean isRanked(String eventName) { return modeOf(eventName) == Mode.RANKED; }

    static String eventName(Mode mode, String format) { return mode.prefix + "_" + format; }

    /** The best-of-three variant of a queue event ends with this ("BotPlay_Standard_BO3", "RankedPlay_Standard_BO3"); see {@link #hasBo3}. */
    static final String BO3 = "_BO3";

    static boolean isBo3(String eventName) { return eventName != null && eventName.endsWith(BO3); }

    /** The queue event without its best-of-three marker. */
    private static String baseName(String eventName) { return isBo3(eventName) ? eventName.substring(0, eventName.length() - BO3.length()) : eventName; }

    /** Games to win in a queue event's match: 2 in a best-of-three, else 1. */
    static int gamesToWin(String eventName) { return isBo3(eventName) && modeOf(eventName) != null ? 2 : 1; }

    /** The mode of a queue event name, null if it is not one of ours. */
    static Mode modeOf(String eventName) {
        if (eventName == null) return null;
        String base = baseName(eventName);
        for (Mode m : Mode.values()) {
            if (!base.startsWith(m.prefix + "_")) continue;
            String format = base.substring(m.prefix.length() + 1);
            if (formats(m).contains(format) && (!isBo3(eventName) || hasBo3(m, format))) return m;
        }
        return null;
    }

    /** Momir Basic's queue: every deck plays as basic lands with Momir Vig's avatar. */
    static boolean isMomir(String eventName) { return modeOf(eventName) == Mode.MOMIR; }

    static boolean isQueueEvent(String eventName) { return modeOf(eventName) != null; }

    /** The format of a queue event, null if it is not one of ours. */
    static String formatOf(String eventName) {
        Mode m = modeOf(eventName);
        return m == null ? null : baseName(eventName).substring(m.prefix.length() + 1);
    }

    /** GetPlayBladeQueueConfig: the "Ranked" list and the "Unranked" one (Bot Play first). */
    JsonArray queueConfig() { return queueConfig(java.util.EnumSet.allOf(Mode.class)); }

    /** The same for the modes this start can play (offline: Bot Play only; the others need the matchmaking server). */
    JsonArray queueConfig(java.util.Set<Mode> modes) {
        JsonArray q = new JsonArray();
        for (Mode m : Mode.values()) {
            if (!modes.contains(m)) continue;
            for (String f : formats(m)) {
                String ev = eventName(m, f);
                q.add(obj("Id", queueId(m, f), "QueueType", queueType(m, f), "LocTitle", "Events/Event_Title_" + ev,
                        "EventNameBO1", ev, "EventNameBO3", hasBo3(m, f) ? ev + BO3 : null, "DeckSizeBO1", null, "DeckSizeBO3", null,
                        "SideBoardBO1", null, "SideBoardBO3", null, "Tags", new JsonArray()));
            }
        }
        return q;
    }

    /** EventInfoV3 entries for EventGetActiveEventsV2.Events. */
    JsonArray eventInfos() { return eventInfos(java.util.EnumSet.allOf(Mode.class)); }

    JsonArray eventInfos(java.util.Set<Mode> modes) {
        JsonArray out = new JsonArray();
        int priority = 200;
        for (Mode m : Mode.values()) {
            if (!modes.contains(m)) continue;
            for (String f : formats(m)) for (boolean bo3 : hasBo3(m, f) ? new boolean[] {false, true} : new boolean[] {false}) {
                String name = eventName(m, f) + (bo3 ? BO3 : "");
                JsonObject data = obj(
                        "TitleRankText", obj("LocKey", "Events/Event_Title_" + name),
                        "DescriptionText", obj("LocKey", "Events/Event_Desc_" + name),
                        "ResignWidget", obj(), "MainButtonWidget", obj(),
                        "SelectedDeckWidget", obj("DeckButtonBehavior", "Editable", "ShowCopyDeckButton", false));
                JsonObject ux = obj("PublicEventName", name, "DisplayPriority", priority++, "PrioritizeBannerIfPlayerHasToken", false,
                        "Parameters", obj(), "Group", "", "EventBladeBehavior", "Queue", "DynamicFilterTagIds", new JsonArray(),
                        "DeckSelectFormat", f, "FactionSealedUXInfo", new JsonArray(), "Prizes", obj(),
                        "EventComponentData", data, "Joinability", "Open");
                out.add(obj("InternalEventName", name, "EventState", "Active", "FormatType", "Constructed",
                        "StartTime", "2020-01-01T00:00:00Z", "LockedTime", "2099-01-01T00:00:00Z", "ClosedTime", "2099-01-01T00:00:00Z",
                        "Flags", flags(m), "PastEntries", obj(), "EntryFees", new JsonArray(), "EventTags", new JsonArray(),
                        "EventUXInfo", ux, "WinCondition", bo3 ? "BestOf3" : "SingleElimination"));
            }
        }
        return out;
    }

    private static JsonArray flags(Mode m) {
        JsonArray a = new JsonArray();
        if (m == Mode.RANKED) a.add("Ranked");
        return a;
    }

    // ---- courses (in memory: a queue has no progress to keep) ---------------------------------------------------

    private final Map<String, JsonObject> courses = new HashMap<>();

    private JsonObject newCourse(String eventName, String module) {
        JsonObject summary = obj("DeckId", UUID.randomUUID().toString(), "Mana", "", "Name", "", "Attributes", new JsonArray(), "Description", "",
                "DeckTileId", 0, "DeckArtId", 0, "IsCompanionValid", false,
                "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
        JsonObject deck = obj("MainDeck", new JsonArray(), "Sideboard", new JsonArray(), "CommandZone", new JsonArray(),
                "Companions", new JsonArray(), "CardSkins", new JsonArray());
        return obj("CourseId", UUID.randomUUID().toString(), "InternalEventName", eventName, "CurrentModule", module, "ModulePayload", "",
                "CourseDeckSummary", summary, "CourseDeck", deck, "CurrentWins", 0, "CurrentLosses", 0, "CardPool", new JsonArray(),
                "CardPoolByCollation", null, "CardStyles", new JsonArray(), "DraftId", null, "TournamentId", null, "MadeChoice", null);
    }

    /** EventJoin: free, straight to deck selection. */
    synchronized Object join(String name) {
        if (!isQueueEvent(name)) return new FrontDoor.Err(7000, "unknown event " + name);
        JsonObject c = newCourse(name, "DeckSelect");
        courses.put(name, c);
        return obj("Course", c, "InventoryInfo", null);
    }

    /** EventSetDeckV3: remember the deck for the event, after checking it against the queue's format. */
    synchronized Object setDeck(String name, JsonObject req, String problem) {
        JsonObject c = courses.get(name);
        if (c == null) return new FrontDoor.Err(7000, "not in event " + name);
        if (problem != null) return new FrontDoor.Err(7000, problem);
        JsonElement summary = get(req, "Summary"), deck = get(req, "Deck");
        if (summary != null) c.add("CourseDeckSummary", summary);
        if (deck != null) c.add("CourseDeck", deck);
        c.addProperty("CurrentModule", "CreateMatch");
        return c;
    }

    /** EventDrop / EventResign: back to Join. */
    synchronized Object drop(String name, boolean wrapped) {
        courses.remove(name);
        JsonObject join = newCourse(name, "Join");
        return wrapped ? obj("Course", join, "InventoryInfo", null) : join;
    }

    synchronized JsonObject course(String name) { return courses.get(name); }

    static String queueLabel(String eventName) {
        Mode m = modeOf(eventName);
        return m == null ? eventName : m == Mode.MOMIR ? m.label : m.label + ": " + formatOf(eventName);
    }

    private static JsonElement get(JsonObject o, String key) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }
}

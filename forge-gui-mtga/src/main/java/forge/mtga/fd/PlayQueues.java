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
 * Standard, Pioneer and Modern. Every queue is an ordinary constructed event named {@code <Mode>_<Format>} whose deck format is
 * the queue's format, so the client's deck picker only offers decks that are valid there; the server checks the submitted deck
 * again ({@link ForgeFormats#problem}). They are not "AIBotMatch" events, so the client runs the normal ladder flow
 * (EventJoin, EventSetDeckV3, EventEnterPairing) and the server starts the match on EventEnterPairing.
 * Casual Play is the entry point for the online matchmaking later: its event name is what a queue service would key on.
 */
final class PlayQueues {
    enum Mode {
        BOT("BotPlay"), CASUAL("CasualPlay");
        final String prefix;
        Mode(String prefix) { this.prefix = prefix; }
    }

    /** Formats offered by every mode (client format names, see {@link ForgeFormats#SPECS}). */
    static final List<String> FORMATS = List.of("Standard", "Pioneer", "Modern");

    /** Play-blade queue id of a mode and format, e.g. "bot-standard". */
    static String queueId(Mode mode, String format) { return (mode == Mode.BOT ? "bot-" : "casual-") + format.toLowerCase(); }

    static String eventName(Mode mode, String format) { return mode.prefix + "_" + format; }

    /** The mode of a queue event name, null if it is not one of ours. */
    static Mode modeOf(String eventName) {
        if (eventName == null) return null;
        for (Mode m : Mode.values()) {
            if (eventName.startsWith(m.prefix + "_") && FORMATS.contains(eventName.substring(m.prefix.length() + 1))) return m;
        }
        return null;
    }

    static boolean isQueueEvent(String eventName) { return modeOf(eventName) != null; }

    /** The format of a queue event, null if it is not one of ours. */
    static String formatOf(String eventName) {
        Mode m = modeOf(eventName);
        return m == null ? null : eventName.substring(m.prefix.length() + 1);
    }

    /** GetPlayBladeQueueConfig: the "Unranked" list, Bot Play first. */
    JsonArray queueConfig() {
        JsonArray q = new JsonArray();
        for (Mode m : Mode.values()) {
            for (String f : FORMATS) {
                String ev = eventName(m, f);
                q.add(obj("Id", queueId(m, f), "QueueType", "Unranked", "LocTitle", "Events/Event_Title_" + ev,
                        "EventNameBO1", ev, "EventNameBO3", null, "DeckSizeBO1", null, "DeckSizeBO3", null,
                        "SideBoardBO1", null, "SideBoardBO3", null, "Tags", new JsonArray()));
            }
        }
        return q;
    }

    /** EventInfoV3 entries for EventGetActiveEventsV2.Events. */
    JsonArray eventInfos() {
        JsonArray out = new JsonArray();
        int priority = 200;
        for (Mode m : Mode.values()) {
            for (String f : FORMATS) {
                String name = eventName(m, f);
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
                        "Flags", new JsonArray(), "PastEntries", obj(), "EntryFees", new JsonArray(), "EventTags", new JsonArray(),
                        "EventUXInfo", ux, "WinCondition", "SingleElimination"));
            }
        }
        return out;
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
        return m == null ? eventName : (m == Mode.BOT ? "Bot Play" : "Casual Play") + ": " + formatOf(eventName);
    }

    private static JsonElement get(JsonObject o, String key) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }
}

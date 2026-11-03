package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.Archenemy;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * "Archenemy: Sparky" as a client event (an event page like the drafts, not a queue): three Brawl matches against Sparky, the AI avatar, each
 * stage a deck of its own ({@link Archenemy#STAGES}). The course's win count is the number of stages beaten (0 to 3), shown on the event page's
 * win track; the next match is stage {@code wins + 1}, and once all three are beaten the last one can be played again. A loss costs nothing
 * (no loss limit, the stage is simply played again). Each stage's first win pays 10,000 Mastery XP, once ({@link #prizeKey}): the
 * coordinator keeps the "already paid" record. The course lives in {@code <dataDir>/events/Archenemy_Sparky.json}.
 */
final class ArchenemyEvent {
    static final String NAME = "Archenemy_Sparky";
    /** Bubbles of the win track: one per stage beaten. */
    static final int STAGES = Archenemy.STAGES.size();

    static boolean is(String eventName) { return NAME.equals(eventName); }

    private final File file;
    private JsonObject course;

    ArchenemyEvent(File dataDir) {
        File dir = new File(dataDir, "events");
        dir.mkdirs();
        this.file = new File(dir, NAME + ".json");
        if (file.isFile()) {
            try {
                course = JsonParser.parseString(Files.readString(file.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                System.out.println("[mtga] FD: unreadable event file " + file + ": " + e);
            }
        }
    }

    // ---- the event page ---------------------------------------------------------------------------------------------

    /** EventInfoV3 for EventGetActiveEventsV2.Events. */
    JsonArray eventInfos() {
        JsonArray chests = new JsonArray();
        for (int w = 0; w <= STAGES; w++) {      // bubble i is i stages beaten: the client indexes the track by the win count
            // every stage beaten is worth 10,000 Mastery XP: the XP icon on the bubble and in its hover popup (as the daily quests' reward)
            boolean prize = w >= 1;
            JsonObject chest = obj("image1", prize ? "ObjectiveIcon_MasteryXP" : null, "image2", null, "image3", null, "prefab", null, "referenceId", null,
                    "headerLocKey", prize ? "Forge/Quest/Reward_XP" : "Events/Forge_Arch_Wins_0", "descriptionLocKey", "Events/Forge_Arch_Wins_Desc_" + w,
                    "quantity", prize ? "10000" : "", "locParams", prize ? obj("number", 10000) : new JsonObject(), "availableDate", "2020-01-01T00:00:00Z");
            chests.add(obj("ChestDescription", chest, "Wins", w));
        }
        JsonObject data = obj(
                "TitleRankText", obj("LocKey", "Events/Event_Title_" + NAME),
                "DescriptionText", obj("LocKey", "Events/Event_Desc_" + NAME),
                "ResignWidget", obj(), "MainButtonWidget", obj(),
                "SelectedDeckWidget", obj("DeckButtonBehavior", "Editable", "ShowCopyDeckButton", true),
                "ByCourseObjectiveTrack", obj("ChestDescriptions", chests));
        JsonObject ux = obj("PublicEventName", NAME, "DisplayPriority", 5, "PrioritizeBannerIfPlayerHasToken", false,
                "Parameters", obj(), "Group", "", "EventBladeBehavior", "EventPage", "DynamicFilterTagIds", new JsonArray(),
                "DeckSelectFormat", ForgeFormats.BRAWL, "FactionSealedUXInfo", new JsonArray(), "Prizes", obj("0", "f0f0f0f0-0000-4000-8000-000000000000"),
                "EventComponentData", data, "Joinability", "Open");
        JsonArray out = new JsonArray();
        out.add(obj("InternalEventName", NAME, "EventState", "Active", "FormatType", "Constructed",
                "StartTime", "2020-01-01T00:00:00Z", "LockedTime", "2099-01-01T00:00:00Z", "ClosedTime", "2099-01-01T00:00:00Z",
                "Flags", new JsonArray(), "PastEntries", obj(), "EntryFees", new JsonArray(), "EventTags", new JsonArray(),
                "EventUXInfo", ux, "WinCondition", "SingleElimination"));
        return out;
    }

    // ---- the course ---------------------------------------------------------------------------------------------------

    private static JsonObject newCourse(String module) {
        JsonObject summary = obj("DeckId", UUID.randomUUID().toString(), "Mana", "", "Name", "", "Attributes", new JsonArray(), "Description", "",
                "DeckTileId", 0, "DeckArtId", 0, "IsCompanionValid", false,
                "PreferredCosmetics", obj("Avatar", "", "Sleeve", "", "Pet", "", "Title", "", "Emotes", new JsonArray()),
                "NetDeckFolderId", null, "IsNetDeck", false, "checkInbox", false);
        JsonObject deck = obj("MainDeck", new JsonArray(), "Sideboard", new JsonArray(), "CommandZone", new JsonArray(),
                "Companions", new JsonArray(), "CardSkins", new JsonArray());
        return obj("CourseId", UUID.randomUUID().toString(), "InternalEventName", NAME, "CurrentModule", module, "ModulePayload", "",
                "CourseDeckSummary", summary, "CourseDeck", deck, "CurrentWins", 0, "CurrentLosses", 0, "CardPool", new JsonArray(),
                "CardPoolByCollation", null, "CardStyles", new JsonArray(), "DraftId", null, "TournamentId", null, "MadeChoice", null);
    }

    /** Back to an empty deck selection (the stages beaten are kept): the page must not show the deck of the last visit. */
    private void clearDeck() {
        JsonObject fresh = newCourse("Join");
        course.add("CourseDeckSummary", fresh.get("CourseDeckSummary"));
        course.add("CourseDeck", fresh.get("CourseDeck"));
    }

    private synchronized void save() {
        try {
            Files.writeString(file.toPath(), course.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[mtga] FD: cannot write event course " + NAME + ": " + e);
        }
    }

    synchronized JsonArray courseList() {
        JsonArray out = new JsonArray();
        if (course != null) out.add(course);
        return out;
    }

    synchronized JsonObject course() { return course; }

    /** EventJoin: free, straight to deck selection; a course in progress (stages beaten) is kept. */
    synchronized Object join() {
        if (course == null) course = newCourse("DeckSelect");
        else if ("Join".equals(str(course, "CurrentModule"))) { clearDeck(); course.addProperty("CurrentModule", "DeckSelect"); }
        save();
        return obj("Course", course, "InventoryInfo", null);
    }

    /** EventSetDeckV3: {@code problem} (the deck is not legal in Brawl) refuses it. */
    synchronized Object setDeck(JsonObject req, String problem) {
        if (course == null) return new FrontDoor.Err(7000, "not in event " + NAME);
        if (problem != null) return new FrontDoor.Err(7000, problem);
        JsonElement summary = get(req, "Summary"), deck = get(req, "Deck");
        if (summary != null) course.add("CourseDeckSummary", summary);
        if (deck != null) course.add("CourseDeck", deck);
        course.addProperty("CurrentModule", "CreateMatch");
        save();
        return course;
    }

    /** EventDrop / EventResign: back to Join; the stages beaten are kept (joining again continues). */
    synchronized Object drop(boolean wrapped) {
        if (course == null) course = newCourse("Join");
        clearDeck();
        course.addProperty("CurrentModule", "Join");
        save();
        return wrapped ? obj("Course", course, "InventoryInfo", null) : course;
    }

    synchronized String module() { return course == null ? "Complete" : str(course, "CurrentModule"); }

    // ---- the matches --------------------------------------------------------------------------------------------------

    /** Stages beaten so far, 0 to {@link #STAGES}. */
    synchronized int cleared() { return course == null ? 0 : course.get("CurrentWins").getAsInt(); }

    /** The stage the next match plays: the first one not beaten, the last one once all are. */
    synchronized Archenemy.Stage next() { return Archenemy.STAGES.get(Math.min(cleared(), STAGES - 1)); }

    /**
     * A match ended. A win of the first stage not beaten yet clears it (true: its XP prize is due); a win of a stage beaten before, or a loss,
     * changes nothing. The course stays at its deck selection / create-match module, the event never ends.
     */
    synchronized boolean recordResult(Archenemy.Stage stage, boolean won) {
        if (course == null) return false;
        boolean first = won && stage.number() == cleared() + 1;
        if (first) course.addProperty("CurrentWins", stage.number());
        save();
        System.out.println("[mtga] FD " + NAME + ": " + stage.name() + (won ? " beaten" : " won against you") + " -> " + cleared() + " of " + STAGES + " stages cleared");
        return first;
    }

    /** The coordinator's prize id of a stage. */
    static String prizeKey(Archenemy.Stage stage) { return stage.key(); }

    private static JsonElement get(JsonObject o, String key) {
        for (Map.Entry<String, JsonElement> e : o.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return e.getValue();
        return null;
    }
}

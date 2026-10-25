package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.mtga.social.QuestService;

import static forge.mtga.fd.FrontDoor.obj;

/**
 * Daily quests through the client's own quest bar (home page objectives; findings in docs/results-rank-mastery.md section 3.5):
 * QuestGetQuests answers ClientQuestCollection {canSwap, newQuests, quests: [ClientQuestDescription]}; a quest's text is the loc key
 * {@code Forge/Quest/<kind>_Text} with {quantity} = the goal (bridge FormatLoc), its reward the chest (mastery XP); QuestSwapQuest {QuestId}
 * answers the new collection; a match report's questUpdates carry startingProgress -> endingProgress (the bar animates) and, for a finished
 * quest, an inventoryUpdate whose context.sourceId is the quest id (the client's reward popup). The quests themselves live on the coordinator
 * ({@link QuestService}). Finished quests are not listed (the client dropped them once claimed; their slot gets a new quest the next day).
 * Quirk of the client: after one swap it greys every quest's swap until the bar is rebuilt (next home visit).
 */
final class DailyQuests {
    private final QuestService service;

    DailyQuests(QuestService service) { this.service = service; }

    boolean enabled() { return service != null && service.online(); }

    /** QuestGetQuests: the last known list (answered at once), refreshed in the background for the next time. */
    JsonObject collection() {
        JsonObject out = collection(service.quests());
        service.refreshAsync();
        return out;
    }

    private static JsonObject collection(JsonArray quests) {
        JsonArray arr = new JsonArray();
        boolean canSwap = false;
        for (JsonElement e : quests) {
            JsonObject q = e.getAsJsonObject();
            if (q.get("done").getAsBoolean()) continue;      // a finished quest leaves the bar once claimed (Arena); its slot is refilled the next day
            arr.add(description(q, q.get("progress").getAsInt(), q.get("progress").getAsInt(), null));
            canSwap |= q.get("canSwap").getAsBoolean();
        }
        return obj("canSwap", canSwap, "newQuests", 0, "quests", arr);
    }

    /** QuestSwapQuest {QuestId}. */
    Object swap(String questId) {
        if (!service.swap(questId)) return new FrontDoor.Err(7000, "This quest can't be swapped today.");
        return collection(service.quests());
    }

    /** A finished match's quest progress as the report's questUpdates (posted to the coordinator: once per match). */
    JsonArray afterMatch(String matchId, JsonObject metrics) {
        JsonArray out = new JsonArray();
        for (JsonElement e : service.progress(matchId, metrics)) {
            JsonObject u = e.getAsJsonObject();
            JsonObject q = obj("questId", u.get("questId").getAsString(), "kind", u.get("kind").getAsString(), "goal", u.get("goal").getAsInt(),
                    "xp", u.has("xp") ? u.get("xp").getAsInt() : xpOf(u.get("questId").getAsString()), "canSwap", canSwap(u.get("questId").getAsString()));
            JsonObject inv = null;
            if (u.has("xp")) {
                JsonObject change = DraftEvents.inventoryChange("QuestReward", u.get("questId").getAsString());
                change.addProperty("SetMasteryProgress", u.get("xp").getAsInt());
                inv = obj("delta", change, "xpGained", u.get("xp").getAsInt(), "context", obj("source", "QuestReward", "sourceId", u.get("questId").getAsString()));
            }
            out.add(description(q, u.get("from").getAsInt(), u.get("to").getAsInt(), inv));
            System.out.println("[mtga] quests: " + u.get("kind").getAsString() + " " + u.get("from").getAsInt() + " -> " + u.get("to").getAsInt() + "/" + u.get("goal").getAsInt()
                    + (u.has("xp") ? " done, +" + u.get("xp").getAsInt() + " XP" : ""));
        }
        return out;
    }

    private int xpOf(String questId) {
        for (JsonElement e : service.quests()) if (e.getAsJsonObject().get("questId").getAsString().equals(questId)) return e.getAsJsonObject().get("xp").getAsInt();
        return 0;
    }

    private boolean canSwap(String questId) {
        for (JsonElement e : service.quests()) if (e.getAsJsonObject().get("questId").getAsString().equals(questId)) return e.getAsJsonObject().get("canSwap").getAsBoolean();
        return false;
    }

    private static JsonObject description(JsonObject q, int from, int to, JsonObject inventoryUpdate) {
        int xp = q.get("xp").getAsInt();
        JsonObject chest = obj("image1", "ObjectiveIcon_MasteryXP", "image2", null, "image3", null, "prefab", null, "referenceId", null,
                "headerLocKey", "Forge/Quest/Reward_XP", "descriptionLocKey", "Forge/Quest/Reward_XP_Desc", "quantity", String.valueOf(xp),
                "locParams", obj("number", xp), "availableDate", "2020-01-01T00:00:00Z");
        return obj("questId", q.get("questId").getAsString(), "goal", q.get("goal").getAsInt(), "locKey", "Forge/Quest/" + q.get("kind").getAsString(),
                "tileResourceId", "00000000-0000-0000-0000-000000000000", "treasureResourceId", "00000000-0000-0000-0000-000000000000",
                "questTrack", "Default", "isNewQuest", false, "endingProgress", to, "startingProgress", from, "canSwap", q.get("canSwap").getAsBoolean(),
                "inventoryUpdate", inventoryUpdate, "chestDescription", chest, "hoursWaitAfterComplete", 0);
    }
}

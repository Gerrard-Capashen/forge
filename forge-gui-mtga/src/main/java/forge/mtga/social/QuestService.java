package forge.mtga.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;

/**
 * The player's daily quests from the coordinator (coordinator/src/quests.ts): three quests, a swap per quest per day, progress from a
 * match's counters (once per match), the XP of a finished quest paid into mastery by the coordinator. The last list is kept so the client's
 * QuestGetQuests is answered at once (the Front Door's handlers must not wait on the network); a swap and a match's progress wait for
 * the coordinator. Without an account (offline start) there are no quests.
 */
public final class QuestService {
    private final CoordinatorSession session;
    private volatile JsonArray quests = new JsonArray();

    /** {@code session} null = offline: no quests. */
    public QuestService(CoordinatorSession session) { this.session = session; }

    public boolean online() { return session != null; }

    /** The quests as last known: [{questId, kind, goal, progress, xp, canSwap, done}]. */
    public JsonArray quests() { return quests; }

    /** At server start and when the client asks again: fetch the list (fills empty slots / a new day's quests on the coordinator). */
    public void refresh() {
        if (session == null) return;
        try {
            quests = session.call("GET", "/me/quests", null).getAsJsonArray("quests");
        } catch (IOException e) {
            System.out.println("[mtga] quests: " + e.getMessage());
        }
    }

    public void refreshAsync() {
        if (session == null) return;
        Thread t = new Thread(this::refresh, "quests-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** QuestSwapQuest: true when the coordinator swapped it (the list is then the new one). */
    public boolean swap(String questId) {
        if (session == null) return false;
        JsonObject body = new JsonObject();
        body.addProperty("questId", questId);
        try {
            quests = session.call("POST", "/me/quests/swap", body).getAsJsonArray("quests");
            return true;
        } catch (IOException e) {
            System.out.println("[mtga] quests: swap refused (" + e.getMessage() + ")");
            return false;
        }
    }

    /** A finished match's counters: the quests it moved [{questId, kind, goal, from, to, xp?}] (empty when none, offline or unreachable). */
    public JsonArray progress(String matchId, JsonObject metrics) {
        if (session == null || metrics == null) return new JsonArray();
        JsonObject body = new JsonObject();
        body.addProperty("match", matchId.length() > 80 ? matchId.substring(0, 80) : matchId);
        body.add("metrics", metrics);
        try {
            JsonObject r = session.call("POST", "/me/quests/progress", body);
            quests = r.getAsJsonArray("quests");
            return r.getAsJsonArray("updates");
        } catch (IOException e) {
            System.out.println("[mtga] quests: " + e.getMessage());
            return new JsonArray();
        }
    }
}

package forge.mtga.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.FileProfileStore;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * The player's rank from the coordinator ({@code GET /me/rank}, coordinator/src/rank.ts) for the client's rank calls, and the rank
 * change of a finished ranked match for its end screen ({@code GET /matches/{id}} -> {@code rank}).
 * The last answer is cached in {@code data/rank.json}: the client's calls are answered from it at once (a stale cache is refreshed in
 * the background), and it is what an offline start shows. The display rank in {@code profile.json} (the badge an opponent sees in
 * MatchCreated) follows every change. Without an account (offline start) everything is the starting rank, Bronze 4.
 */
public final class RankService {
    private static final Gson GSON = new Gson();
    private static final long STALE_MS = 20_000;
    private static final String[] CLASSES = {"Bronze", "Silver", "Gold", "Platinum", "Diamond", "Mythic"};

    private final Accounts accounts;
    private final FileProfileStore profiles;
    private final File cacheFile;
    private volatile JsonObject cache;
    private volatile long fetchedAt;
    private volatile boolean refreshing;
    private volatile String token;
    private volatile long tokenAt;

    /** {@code accounts} null = offline: the starting rank, nothing is fetched. */
    public RankService(Accounts accounts, FileProfileStore profiles, File dataDir) {
        this.accounts = accounts;
        this.profiles = profiles;
        this.cacheFile = dataDir == null ? null : new File(dataDir, "rank.json");
        this.cache = accounts == null ? null : readCache();
        if (accounts != null) refreshAsync();
    }

    public boolean online() { return accounts != null; }

    /** The coordinator session token (24 h), for the ranked queue ticket too. */
    public synchronized String session() throws IOException {
        if (accounts == null) throw new IOException("offline");
        if (token == null || System.currentTimeMillis() - tokenAt > 20 * 3_600_000L) {
            token = accounts.login(profiles.load().playerId);
            tokenAt = System.currentTimeMillis();
        }
        return token;
    }

    private JsonObject call(String method, String path) throws IOException {
        try {
            return accounts.call(method, path, null, session());
        } catch (Accounts.ApiException e) {
            if (e.status != 401) throw e;
            synchronized (this) { token = null; }                                // expired: sign in again once
            return accounts.call(method, path, null, session());
        }
    }

    // ---- the client's rank calls ----------------------------------------------------------------------------------

    /** RankGetCombinedRankInfo (PlayerRankingInfo). */
    public JsonObject combinedRankInfo() {
        if (accounts != null && System.currentTimeMillis() - fetchedAt > STALE_MS) refreshAsync();
        JsonObject pools = cache != null && cache.has("pools") ? cache.getAsJsonObject("pools") : new JsonObject();
        int season = cache != null && cache.has("season") ? cache.get("season").getAsInt() : 1;
        JsonObject out = new JsonObject();
        put(out, "constructed", pool(pools, "constructed"), season);
        put(out, "limited", pool(pools, "limited"), season);
        return out;
    }

    private static JsonObject pool(JsonObject pools, String name) {
        JsonElement p = pools.get(name);
        return p != null && p.isJsonObject() ? p.getAsJsonObject() : new JsonObject();
    }

    private static void put(JsonObject out, String prefix, JsonObject p, int season) {
        out.addProperty(prefix + "SeasonOrdinal", season);
        out.addProperty(prefix + "Class", str(p, "class", "Bronze"));
        out.addProperty(prefix + "Level", num(p, "level", 4));
        out.addProperty(prefix + "Step", num(p, "step", 0));
        out.addProperty(prefix + "MatchesWon", num(p, "wins", 0));
        out.addProperty(prefix + "MatchesLost", num(p, "losses", 0));
        out.addProperty(prefix + "MatchesDrawn", num(p, "draws", 0));
        out.addProperty(prefix + "Percentile", p.has("percentile") ? p.get("percentile").getAsDouble() : 0);
        out.addProperty(prefix + "LeaderboardPlace", num(p, "place", 0));
    }

    /** RankGetSeasonAndRankDetails (SeasonAndRankInfo): the ladder shape tells the client how many pips each level has. */
    public JsonObject seasonAndRankDetails() {
        JsonObject c = cache;
        JsonArray ladder = new JsonArray();
        if (c != null && c.has("ladder")) {
            for (JsonElement e : c.getAsJsonArray("ladder")) {
                JsonObject l = e.getAsJsonObject();
                ladder.add(rankInfo(l.get("class").getAsString(), l.get("level").getAsInt(), l.get("steps").getAsInt()));
            }
        } else {
            for (int cls = 0; cls < 5; cls++) for (int lv = 4; lv >= 1; lv--) ladder.add(rankInfo(CLASSES[cls], lv, cls == 0 ? 4 : 6));
            ladder.add(rankInfo("Mythic", 1, 0));
        }
        JsonObject season = new JsonObject();
        season.addProperty("seasonOrdinal", c != null && c.has("season") ? c.get("season").getAsInt() : 1);
        season.addProperty("seasonStartTime", c != null && c.has("seasonStart") ? c.get("seasonStart").getAsString() : "2026-10-01T00:00:00Z");
        season.addProperty("seasonEndTime", c != null && c.has("seasonEnd") ? c.get("seasonEnd").getAsString() : "2099-01-01T00:00:00Z");
        season.add("seasonLimitedRewards", new JsonObject());
        season.add("seasonConstructedRewards", new JsonObject());
        season.addProperty("minMatches", 0);
        JsonObject out = new JsonObject();
        out.add("currentSeason", season);
        out.add("limitedRankInfo", ladder.deepCopy());
        out.add("constructedRankInfo", ladder);
        return out;
    }

    private static JsonObject rankInfo(String cls, int level, int steps) {
        JsonObject o = new JsonObject();
        o.addProperty("rankClass", cls);
        o.addProperty("level", level);
        o.addProperty("steps", steps);
        return o;
    }

    // ---- after a ranked match ---------------------------------------------------------------------------------------

    /**
     * The coordinator's rank change for this player in a match, waiting up to {@code maxMs} (the other seat's report may not be in yet):
     * {season, result, reason, lossProtected, old{class,level,step}, new{...}, place, percentile} or null. Updates the cache and the profile.
     */
    public JsonObject awaitMatch(String matchId, long maxMs) {
        if (accounts == null) return null;
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            try {
                JsonObject v = call("GET", "/matches/" + matchId);
                if (v.has("rank") && v.get("rank").isJsonObject()) {
                    JsonObject rank = v.getAsJsonObject("rank");
                    applyLocally(rank);
                    refreshAsync();
                    return rank;
                }
                String status = v.has("status") ? v.get("status").getAsString() : "pending";
                if (status.equals("disputed") || status.equals("void")) return null;
            } catch (IOException e) {
                System.out.println("[mtga] rank: " + e.getMessage());
            }
            if (System.currentTimeMillis() + 400 > end) return null;
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
    }

    /** PlayerRankProgress of a rank change (EventGetMatchResultReport.rankUpdates). */
    public static JsonObject progress(JsonObject rank) {
        JsonObject o = new JsonObject(), from = rank.getAsJsonObject("old"), to = rank.getAsJsonObject("new");
        o.addProperty("seasonOrdinal", num(rank, "season", 1));
        o.addProperty("oldClass", str(from, "class", "Bronze"));
        o.addProperty("oldLevel", num(from, "level", 4));
        o.addProperty("oldStep", num(from, "step", 0));
        o.addProperty("newClass", str(to, "class", "Bronze"));
        o.addProperty("newLevel", num(to, "level", 4));
        o.addProperty("newStep", num(to, "step", 0));
        o.addProperty("wasLossProtected", rank.has("lossProtected") && rank.get("lossProtected").getAsBoolean());
        o.addProperty("rankUpdateType", "limited".equals(str(rank, "pool", "constructed")) ? "Limited" : "Constructed");
        return o;
    }

    /** Client_MythicRatingUpdates of a rank change, null below Mythic. */
    public static JsonObject mythic(JsonObject rank, double oldPercentile) {
        if (!"Mythic".equals(str(rank.getAsJsonObject("new"), "class", ""))) return null;
        JsonObject o = new JsonObject();
        o.addProperty("oldMythicPercentile", oldPercentile);
        o.addProperty("newMythicPercentile", rank.has("percentile") ? rank.get("percentile").getAsDouble() : 0);
        o.addProperty("newMythicLeaderboardPlacement", num(rank, "place", 0));
        return o;
    }

    /** The cached Mythic percentile of a pool (before a match changes it). */
    public double percentile(String pool) {
        JsonObject c = cache;
        if (c == null || !c.has("pools")) return 0;
        JsonObject p = pool(c.getAsJsonObject("pools"), pool);
        return p.has("percentile") ? p.get("percentile").getAsDouble() : 0;
    }

    /** The new pips go into the cache at once (the client asks for the combined rank before a refresh could answer). */
    private synchronized void applyLocally(JsonObject rank) {
        String poolName = str(rank, "pool", "constructed");
        JsonObject c = cache != null ? cache.deepCopy() : new JsonObject();
        if (!c.has("pools")) c.add("pools", new JsonObject());
        JsonObject p = pool(c.getAsJsonObject("pools"), poolName);
        JsonObject to = rank.getAsJsonObject("new");
        p.addProperty("class", str(to, "class", "Bronze"));
        p.addProperty("level", num(to, "level", 4));
        p.addProperty("step", num(to, "step", 0));
        if ("applied".equals(str(rank, "reason", ""))) {
            String key = switch (str(rank, "result", "")) { case "win" -> "wins"; case "loss" -> "losses"; case "draw" -> "draws"; default -> null; };
            if (key != null) p.addProperty(key, num(p, key, 0) + 1);
        }
        p.addProperty("place", num(rank, "place", 0));
        if (rank.has("percentile")) p.add("percentile", rank.get("percentile"));
        c.getAsJsonObject("pools").add(poolName, p);
        store(c);
    }

    // ---- cache ------------------------------------------------------------------------------------------------------

    /** Fetches {@code /me/rank} in the background (one at a time). */
    public void refreshAsync() {
        if (accounts == null || refreshing) return;
        refreshing = true;
        Thread t = new Thread(() -> {
            try {
                store(call("GET", "/me/rank"));
                fetchedAt = System.currentTimeMillis();
            } catch (IOException e) {
                System.out.println("[mtga] rank: " + e.getMessage() + (cache != null ? " (showing the last known rank)" : ""));
                fetchedAt = System.currentTimeMillis();          // do not hammer an unreachable coordinator; retry when stale again
            } finally {
                refreshing = false;
            }
        }, "rank-refresh");
        t.setDaemon(true);
        t.start();
    }

    /** Blocking refresh (tests). */
    public void refresh() throws IOException {
        if (accounts == null) return;
        store(call("GET", "/me/rank"));
        fetchedAt = System.currentTimeMillis();
    }

    private synchronized void store(JsonObject answer) {
        cache = answer;
        if (cacheFile != null) {
            try {
                Files.writeString(cacheFile.toPath(), GSON.toJson(answer), StandardCharsets.UTF_8);
            } catch (IOException e) {
                System.out.println("[mtga] rank: cannot write " + cacheFile + " (" + e.getMessage() + ")");
            }
        }
        JsonObject c = pool(answer.has("pools") ? answer.getAsJsonObject("pools") : new JsonObject(), "constructed");
        String cls = str(c, "class", "Bronze");
        int tier = num(c, "level", 4);
        if (profiles != null) {
            forge.mtga.profile.Profile now = profiles.load();
            if (!cls.equals(now.rank.rankClass) || tier != now.rank.tier) {
                profiles.update(p -> { p.rank.rankClass = cls; p.rank.tier = tier; });
            }
        }
    }

    private JsonObject readCache() {
        if (cacheFile == null || !cacheFile.isFile()) return null;
        try {
            return JsonParser.parseString(Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String k, String d) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d; }

    private static int num(JsonObject o, String k, int d) { return o != null && o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : d; }
}

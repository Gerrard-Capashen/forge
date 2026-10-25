package forge.mtga.social;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.FileProfileStore;
import forge.mtga.profile.Mastery;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's mastery track from the coordinator (coordinator/src/mastery.ts): the track itself ({@code GET /mastery/track}, one
 * cosmetic per level), the player's XP and level and the cosmetics granted so far ({@code GET /me/mastery}), XP for local bot matches
 * ({@code POST /me/xp/bot}) and the XP of an online match (its verdict, {@code GET /matches/{id}}).
 *
 * <p>The coordinator owns XP and grants; this side keeps a cache in {@code data/mastery.json} (answers at once, used when the coordinator
 * cannot be reached) and writes the ownership into {@code profile.json}: online, {@code owned.all} is off and the owned lists are the grants
 * (the always-owned set is added by the profile code). Without an account (offline start) {@code owned.all} is on: everything unlocked,
 * no track. {@code claimedLevel} is local and only drives the client's reward popups: rewards of levels above it are shown as
 * claimable, the client claims them (GraphProcessV2) and the popup shows the cosmetic.
 */
public final class MasteryService {
    private static final Gson GSON = new Gson();
    private static final String[] LISTS = {"avatars", "sleeves", "pets", "emotes", "titles"};

    private final CoordinatorSession session;
    private final FileProfileStore profiles;
    private final File cacheFile;
    private JsonObject cache;          // {track: {version, items}, progress: {xp, level, owned...}, claimedLevel}

    /** {@code session} null = offline: everything owned, no track. */
    public MasteryService(CoordinatorSession session, FileProfileStore profiles, File dataDir) {
        this.session = session;
        this.profiles = profiles;
        this.cacheFile = dataDir == null ? null : new File(dataDir, "mastery.json");
        this.cache = session == null ? new JsonObject() : read();
        if (session == null) {
            if (profiles != null && !profiles.load().owned.all) profiles.update(p -> p.owned.all = true);
        } else {
            applyOwnership();                                    // the last known grants, before the client asks
        }
    }

    public boolean online() { return session != null; }

    /** The track is known (cached or fetched): the Front Door serves the mastery graph. */
    public synchronized boolean hasTrack() { return online() && cache.has("track"); }

    /** At server start: fetch the track (when its version changed) and the progress; blocking, a few seconds at most. */
    public void sync() {
        if (session == null) return;
        try {
            JsonObject t = session.publicCall("/mastery/track");
            synchronized (this) {
                cache.add("track", t);
            }
            refresh();
        } catch (IOException e) {
            System.out.println("[mtga] mastery: " + e.getMessage() + (hasTrack() ? " (showing the last known track)" : ""));
        }
    }

    /** Re-reads the player's XP and grants. */
    public void refresh() throws IOException {
        if (session == null) return;
        store(session.call("GET", "/me/mastery", null));
    }

    // ---- what the Front Door draws ----------------------------------------------------------------------------------

    /** The track's items: {kind, cosmetic id}; level n gives items[n-1]. */
    public synchronized List<String[]> track() {
        List<String[]> out = new ArrayList<>();
        if (!cache.has("track")) return out;
        for (JsonElement e : cache.getAsJsonObject("track").getAsJsonArray("items")) {
            JsonArray a = e.getAsJsonArray();
            out.add(new String[] {a.get(0).getAsString(), a.get(1).getAsString()});
        }
        return out;
    }

    public synchronized int xp() { return progress().has("xp") ? progress().get("xp").getAsInt() : 0; }

    public synchronized int level() { return progress().has("level") ? progress().get("level").getAsInt() : 0; }

    /** Levels whose reward the client has shown (first start: the current level, so a returning player gets no flood of popups). */
    public synchronized int claimedLevel() {
        if (!cache.has("claimedLevel")) return level();
        return cache.get("claimedLevel").getAsInt();
    }

    /** The client claimed the rewards of these levels: they are shown from now on as taken. */
    public synchronized void claimed(int upToLevel) {
        cache.addProperty("claimedLevel", Math.max(claimedLevel(), Math.min(upToLevel, level())));
        write();
    }

    private JsonObject progress() { return cache.has("progress") ? cache.getAsJsonObject("progress") : new JsonObject(); }

    // ---- XP ---------------------------------------------------------------------------------------------------------

    /** A local bot match finished: its XP (0 when capped or offline). */
    public int botMatch(String matchId) {
        if (session == null) return 0;
        JsonObject body = new JsonObject();
        body.addProperty("match", matchId.length() > 80 ? matchId.substring(0, 80) : matchId);
        try {
            JsonObject r = session.call("POST", "/me/xp/bot", body);
            store(r);
            int gained = r.has("gained") ? r.get("gained").getAsInt() : 0;
            System.out.println("[mtga] mastery: bot match " + matchId + " +" + gained + " XP (total " + xp() + ", level " + level() + ")");
            return gained;
        } catch (IOException e) {
            System.out.println("[mtga] mastery: " + e.getMessage());
            return 0;
        }
    }

    /**
     * An online match's verdict once it is settled (status no longer pending), waiting up to {@code maxMs} for the other seat's report;
     * it carries {@code rank} (ranked) and {@code mastery} {xp} when they were applied. The progress is re-read when XP was paid.
     */
    public JsonObject awaitVerdict(String matchId, long maxMs) {
        if (session == null) return null;
        long end = System.currentTimeMillis() + maxMs;
        while (true) {
            try {
                JsonObject v = session.call("GET", "/matches/" + matchId, null);
                String status = v.has("status") ? v.get("status").getAsString() : "pending";
                if (!status.equals("pending")) {
                    if (v.has("mastery")) refresh();
                    return v;
                }
            } catch (IOException e) {
                System.out.println("[mtga] mastery: " + e.getMessage());
            }
            if (System.currentTimeMillis() + 400 > end) return null;
            try { Thread.sleep(400); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
    }

    // ---- cache and ownership ----------------------------------------------------------------------------------------

    private synchronized void store(JsonObject progress) {
        JsonObject p = progress.deepCopy();
        p.remove("gained");
        boolean first = !cache.has("progress");
        cache.add("progress", p);
        if (first && !cache.has("claimedLevel")) cache.addProperty("claimedLevel", level());
        write();
        applyOwnership();
    }

    /** profile.json: owned.all off and the granted lists (the profile code adds the always-owned set). */
    private void applyOwnership() {
        if (profiles == null) return;
        JsonObject owned;
        synchronized (this) {
            JsonObject p = progress();
            owned = p.has("owned") ? p.getAsJsonObject("owned") : new JsonObject();
        }
        List<List<String>> lists = new ArrayList<>();
        for (String k : LISTS) {
            List<String> l = new ArrayList<>();
            if (owned.has(k)) for (JsonElement e : owned.getAsJsonArray(k)) if (!Mastery.ALWAYS_OWNED.contains(e.getAsString())) l.add(e.getAsString());
            lists.add(l);
        }
        forge.mtga.profile.Profile now = profiles.load();
        if (!now.owned.all && now.owned.avatars.equals(lists.get(0)) && now.owned.sleeves.equals(lists.get(1)) && now.owned.pets.equals(lists.get(2))
                && now.owned.emotes.equals(lists.get(3)) && now.owned.titles.equals(lists.get(4))) return;
        profiles.update(p -> {
            p.owned.all = false;
            p.owned.avatars = lists.get(0);
            p.owned.sleeves = lists.get(1);
            p.owned.pets = lists.get(2);
            p.owned.emotes = lists.get(3);
            p.owned.titles = lists.get(4);
        });
    }

    private JsonObject read() {
        if (cacheFile == null || !cacheFile.isFile()) return new JsonObject();
        try {
            return JsonParser.parseString(Files.readString(cacheFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            return new JsonObject();
        }
    }

    private void write() {
        if (cacheFile == null) return;
        try {
            Files.writeString(cacheFile.toPath(), GSON.toJson(cache), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.out.println("[mtga] mastery: cannot write " + cacheFile + " (" + e.getMessage() + ")");
        }
    }
}

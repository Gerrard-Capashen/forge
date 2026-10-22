package forge.mtga.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** {@code profile.json} in the data directory; written atomically (temp file + move). */
public final class FileProfileStore implements ProfileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final File file;
    private final File legacyPrefs;
    private Profile profile;

    public FileProfileStore(File dataDir) {
        this.file = new File(dataDir, "profile.json");
        this.legacyPrefs = new File(dataDir, "preferences.json");
    }

    public File file() { return file; }

    @Override
    public synchronized Profile load() { return copy(current()); }

    @Override
    public synchronized Profile update(Consumer<Profile> change) {
        Profile p = current();
        change.accept(p);
        normalize(p);
        p.updated = Instant.now().toString();
        write(p);
        return copy(p);
    }

    private Profile current() {
        if (profile == null) profile = read();
        return profile;
    }

    private Profile read() {
        if (file.isFile()) {
            try {
                Profile p = GSON.fromJson(Files.readString(file.toPath(), StandardCharsets.UTF_8), Profile.class);
                if (p != null && p.playerId != null && !p.playerId.isBlank()) {
                    boolean complete = p.tag != null && !p.tag.isBlank();     // the client bridge reads the tag from the file
                    if (p.version < Profile.VERSION) migrate(p);
                    normalize(p);
                    if (!complete) write(p);
                    return p;
                }
            } catch (Exception e) {
                System.out.println("[mtga] profile: cannot read " + file + ": " + e);
            }
            // unreadable: keep it for inspection and start again (a new player id)
            File bad = new File(file.getParentFile(), "profile.json.bad-" + System.currentTimeMillis());
            if (file.renameTo(bad)) System.out.println("[mtga] profile: moved the unreadable file to " + bad.getName());
        }
        Profile p = new Profile();
        p.playerId = UUID.randomUUID().toString();
        p.created = Instant.now().toString();
        p.updated = p.created;
        importLegacyPreferences(p);
        normalize(p);
        write(p);
        System.out.println("[mtga] profile: created " + file + " (player " + p.playerId + ")");
        return p;
    }

    /** Hook for later versions of the document. */
    private static void migrate(Profile p) { p.version = Profile.VERSION; }

    /** First profile of an install that already ran: keep the preferences the old {@code preferences.json} held. */
    private void importLegacyPreferences(Profile p) {
        try {
            if (!legacyPrefs.isFile()) return;
            JsonObject o = JsonParser.parseString(Files.readString(legacyPrefs.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) p.preferences.put(e.getKey(), e.getValue().isJsonNull() ? null : e.getValue().getAsString());
            System.out.println("[mtga] profile: imported " + p.preferences.size() + " preferences from preferences.json");
        } catch (Exception e) {
            System.out.println("[mtga] profile: cannot import " + legacyPrefs + ": " + e);
        }
    }

    private static void normalize(Profile p) {
        if (p.equipped == null) p.equipped = new Profile.Equipped();
        if (p.equipped.emotes == null) p.equipped.emotes = new java.util.ArrayList<>(Profile.DEFAULT_EMOTES);
        if (p.owned == null) p.owned = new Profile.Owned();
        if (p.rank == null) p.rank = new Profile.Rank();
        if (p.preferences == null) p.preferences = new java.util.TreeMap<>();
        p.displayName = Profile.cleanName(p.displayName);
        p.tag = p.tag();
    }

    private void write(Profile p) {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getParentFile(), "profile.json.tmp");
            Files.writeString(tmp.toPath(), GSON.toJson(p), StandardCharsets.UTF_8);
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.out.println("[mtga] profile: cannot write " + file + ": " + e);
        }
    }

    private static Profile copy(Profile p) { return GSON.fromJson(GSON.toJson(p), Profile.class); }
}

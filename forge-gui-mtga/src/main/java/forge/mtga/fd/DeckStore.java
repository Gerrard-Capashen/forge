package forge.mtga.fd;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Player decks as JSON files ({@code <dir>/<deckId>.json} = {"Summary": DTO_DeckSummaryV3, "Deck": Deck}), stored
 * exactly as the client sent them in DeckUpsertDeckV3 so they round-trip through the client's strict JSON converters.
 */
public final class DeckStore {
    private final File dir;

    public DeckStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
    }

    public File dir() { return dir; }

    public synchronized List<JsonObject> all() {
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        List<JsonObject> out = new ArrayList<>();
        if (files == null) return out;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File f : files) {
            JsonObject o = read(f);
            if (o != null && o.has("Summary") && o.has("Deck")) out.add(o);
        }
        return out;
    }

    public synchronized JsonObject get(String deckId) {
        File f = file(deckId);
        return f.isFile() ? read(f) : null;
    }

    public synchronized void put(String deckId, JsonObject summary, JsonObject deck) throws IOException {
        JsonObject o = new JsonObject();
        o.add("Summary", summary);
        o.add("Deck", deck);
        Files.writeString(file(deckId).toPath(), o.toString(), StandardCharsets.UTF_8);
    }

    public synchronized boolean delete(String deckId) {
        File f = file(deckId);
        return f.isFile() && f.delete();
    }

    private File file(String deckId) {
        String safe = deckId.toLowerCase().replaceAll("[^0-9a-f-]", "");
        if (safe.isEmpty()) throw new IllegalArgumentException("bad deck id " + deckId);
        return new File(dir, safe + ".json");
    }

    private static JsonObject read(File f) {
        try {
            return JsonParser.parseString(Files.readString(f.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) {
            System.out.println("[mtga] FD: unreadable deck file " + f + ": " + e);
            return null;
        }
    }
}

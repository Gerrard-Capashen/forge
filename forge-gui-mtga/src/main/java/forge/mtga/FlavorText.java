package forge.mtga;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.card.CardRules;
import forge.card.CardSplitType;
import forge.item.PaperCard;

import java.io.File;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Flavor text for the cards the builder generates. Forge's card scripts have none, and the client only draws it for a card without
 * rules text (Pearled Unicorn, Keepers of the Faith: an empty text box otherwise). It comes from Scryfall, only for such cards
 * (one batched request per 75), and is cached by card name in {@code flavor.json} next to the card DB snapshot, so a rebuild offline
 * or after a failed request still has what was fetched before. A card Scryfall has no flavor text for is cached as "" and not asked again.
 */
final class FlavorText {
    private static final String API = "https://api.scryfall.com/cards/collection";
    private final File cacheFile;
    private final Map<String, String> byName = new HashMap<>();

    private FlavorText(File cacheFile) { this.cacheFile = cacheFile; }

    /** Loads the cache and fetches the flavor text of the given cards that need it and are not cached yet. Never throws: no network = no new text. */
    static FlavorText load(File dataDir, List<PaperCard> cards) {
        FlavorText ft = new FlavorText(new File(dataDir, "flavor.json"));
        try {
            if (ft.cacheFile.isFile()) {
                JsonObject o = JsonParser.parseString(Files.readString(ft.cacheFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : o.entrySet()) ft.byName.put(e.getKey(), e.getValue().getAsString());
            }
        } catch (Exception e) {
            System.out.println("[carddb] flavor cache unreadable, ignored: " + e);
        }
        Map<String, String> missing = new HashMap<>();   // name -> Forge set code
        for (PaperCard pc : cards) if (needs(pc) && !ft.byName.containsKey(pc.getName())) missing.putIfAbsent(pc.getName(), pc.getEdition());
        if (missing.isEmpty()) return ft;
        System.out.println("[carddb] fetching flavor text of " + missing.size() + " cards without rules text");
        try {
            ft.fetch(missing);
        } catch (Exception e) {
            System.out.println("[carddb] flavor text fetch stopped (" + e + "); cards not fetched keep an empty text box");
        }
        try {
            Files.writeString(ft.cacheFile.toPath(), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(ft.byName), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.out.println("[carddb] could not write flavor cache: " + e);
        }
        return ft;
    }

    /** A single-face card with no rules text: the only kind whose flavor text the client shows. */
    static boolean needs(PaperCard pc) {
        CardRules r = pc.getRules();
        if (r.getSplitType() != CardSplitType.None) return false;
        String oracle = r.getMainPart().getOracleText();
        return oracle == null || oracle.isBlank();
    }

    /** The flavor text for a card name, or null. */
    String get(String name) {
        String t = byName.get(name);
        // Scryfall marks italics (a book title) with *...*; the client draws the whole text in italics itself
        return t == null || t.isBlank() ? null : t.replace("*", "");
    }

    private void fetch(Map<String, String> missing) throws Exception {
        List<String> names = new ArrayList<>(missing.keySet());
        for (int i = 0; i < names.size(); i += 75) {
            List<String> batch = names.subList(i, Math.min(names.size(), i + 75));
            JsonArray ids = new JsonArray();
            for (String n : batch) {
                JsonObject id = new JsonObject();
                id.addProperty("name", n);
                ids.add(id);
            }
            JsonObject body = new JsonObject();
            body.add("identifiers", ids);
            JsonObject res = post(body.toString());
            for (JsonElement e : res.getAsJsonArray("data")) {
                JsonObject card = e.getAsJsonObject();
                String flavor = card.has("flavor_text") ? card.get("flavor_text").getAsString() : "";
                String name = card.get("name").getAsString();
                for (String n : batch) if (n.equalsIgnoreCase(name) || name.startsWith(n + " //")) byName.put(n, flavor);
            }
            // asked and answered (found or not): do not ask again
            for (String n : batch) byName.putIfAbsent(n, "");
            Thread.sleep(120);   // Scryfall asks for 50-100 ms between requests
        }
    }

    private static JsonObject post(String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "mtga-forge-carddb/1.0");
        try (OutputStream out = c.getOutputStream()) { out.write(json.getBytes(StandardCharsets.UTF_8)); }
        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
        try (java.io.InputStream in = c.getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
}

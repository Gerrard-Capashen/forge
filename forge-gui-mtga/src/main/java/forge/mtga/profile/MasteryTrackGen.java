package forge.mtga.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Writes the mastery track: every cosmetic of the client's catalogue that is not always owned, one per level, in an order that
 * is random but the same for every player (coordinator/src/mastery.ts). The file is the single source of truth
 * (the coordinator grants from it, the Front Door draws the track from it): {@code coordinator/src/mastery-track.json}.
 *
 * <p>Append-only: the levels of an existing file never move; cosmetics a client update added are shuffled among themselves
 * (seeded by the file's length) and inserted at random places after the first {@link #PROTECTED_ITEMS} items, except the champion title ({@link Mastery#CHAMPION_TITLE}), which is always the last
 * level. Usage: {@code MasteryTrackGen <client dir> <track json>}.
 */
public final class MasteryTrackGen {
    private static final long SEED = 20261025L;
    /** Cosmetics a client update added are mixed into the track from this item on (level 150): the first levels are what players
     *  reach, and what they were granted must never change. The production maximum was level 9 on 2026-11-03. */
    private static final int PROTECTED_ITEMS = 300;

    private MasteryTrackGen() { }

    public static void main(String[] args) throws Exception {
        CosmeticCatalog catalog = CosmeticCatalog.load(new File(args.length > 0 ? args[0] : "../../client"));
        File out = new File(args.length > 1 ? args[1] : "../../coordinator/src/mastery-track.json");
        System.out.println("catalogue: " + catalog.summary());
        if (catalog.avatars().isEmpty()) throw new IllegalStateException("empty catalogue: wrong client folder?");

        List<String[]> items = new ArrayList<>();                     // {kind, id}
        Set<String> known = new LinkedHashSet<>();
        if (out.isFile()) {
            JsonObject old = JsonParser.parseString(Files.readString(out.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : old.getAsJsonArray("items")) {
                JsonArray a = e.getAsJsonArray();
                if (a.get(1).getAsString().equals(Mastery.CHAMPION_TITLE)) continue;     // re-added at the end
                if (Mastery.ALWAYS_OWNED.contains(a.get(1).getAsString())) continue;    // became always owned: off the track
                items.add(new String[] {a.get(0).getAsString(), a.get(1).getAsString()});
                known.add(a.get(1).getAsString());
            }
        }
        List<String[]> fresh = new ArrayList<>();
        add(fresh, known, "avatar", catalog.avatars().stream().filter(id -> !Mastery.ALWAYS_OWNED.contains(id)).sorted().toList());
        add(fresh, known, "sleeve", catalog.sleeves().stream().filter(id -> !Mastery.ALWAYS_OWNED.contains(id)).sorted().toList());
        add(fresh, known, "pet", catalog.pets().stream().map(CosmeticCatalog.Pet::id).sorted().toList());
        add(fresh, known, "emote", catalog.phrases().stream().filter(id -> !Mastery.ALWAYS_OWNED.contains(id)).sorted().toList());
        add(fresh, known, "emote", catalog.stickers().stream().sorted().toList());
        add(fresh, known, "title", catalog.titles().stream().map(CosmeticCatalog.Title::id)
                .filter(id -> !Mastery.ALWAYS_OWNED.contains(id) && !id.equals(Mastery.CHAMPION_TITLE)).sorted().toList());
        Random rnd = new Random(SEED + items.size());
        Collections.shuffle(fresh, rnd);
        if (items.size() <= PROTECTED_ITEMS) items.addAll(fresh);                       // first generation: just the shuffled catalogue
        else for (String[] f : fresh) items.add(PROTECTED_ITEMS + rnd.nextInt(items.size() - PROTECTED_ITEMS + 1), f);   // later: spread over the unreached levels
        if (catalog.hasTitle(Mastery.CHAMPION_TITLE)) items.add(new String[] {"title", Mastery.CHAMPION_TITLE});

        JsonArray arr = new JsonArray();
        for (String[] it : items) {
            JsonArray a = new JsonArray();
            a.add(it[0]);
            a.add(it[1]);
            arr.add(a);
        }
        JsonObject o = new JsonObject();
        o.addProperty("version", items.size());
        o.add("items", arr);
        // one item per line: diffs of a regenerated file stay readable
        Gson gson = new GsonBuilder().disableHtmlEscaping().create();
        StringBuilder sb = new StringBuilder("{\"version\": ").append(items.size()).append(",\n\"items\": [\n");
        for (int i = 0; i < arr.size(); i++) sb.append(gson.toJson(arr.get(i))).append(i + 1 < arr.size() ? ",\n" : "\n");
        sb.append("]}\n");
        Files.writeString(out.toPath(), sb.toString(), StandardCharsets.UTF_8);
        System.out.println("mastery track: " + items.size() + " levels (" + fresh.size() + " new), last " + items.get(items.size() - 1)[1] + " -> " + out);
    }

    private static void add(List<String[]> out, Set<String> known, String kind, List<String> ids) {
        for (String id : ids) if (known.add(id)) out.add(new String[] {kind, id});
    }
}

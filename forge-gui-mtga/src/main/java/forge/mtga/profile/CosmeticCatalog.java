package forge.mtga.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Every cosmetic the client can show: avatars, sleeves (card backs), pets, emotes (phrases and stickers) and titles, with
 * the ids the client uses. Nothing is stored here: the lists are read from the player's own MTGA install at run time, from
 * the client's asset lookup trees ({@code Downloads/ALT/ALT_*.mtga}, JSON; an extractor node maps every possible value, i.e.
 * every cosmetic id, to a node) and its localisation database (titles). See docs/profile.md section 2.
 */
public final class CosmeticCatalog {
    /** A pet is chosen by name and variant; the client's id for it is {@code name.variant}. */
    public record Pet(String name, String variant) {
        public String id() { return name + "." + variant; }
    }

    public record Title(String id, String locKey) { }

    /** Avatars every new account starts with (the client falls back to one of these for an avatar it cannot draw). */
    private static final List<String> DEFAULT_AVATARS = List.of("Avatar_Basic_AjaniGoldmane", "Avatar_Basic_ChandraNalaar",
            "Avatar_Basic_JaceBeleren", "Avatar_Basic_LilianaVess", "Avatar_Basic_VivienReid");

    private final List<String> avatars = new ArrayList<>();
    private final List<String> sleeves = new ArrayList<>();
    private final List<Pet> pets = new ArrayList<>();
    private final List<String> phrases = new ArrayList<>();
    private final List<String> stickers = new ArrayList<>();
    private final List<Title> titles = new ArrayList<>();
    private final Set<String> defaultAvatars = new LinkedHashSet<>();

    private CosmeticCatalog() { }

    public List<String> avatars() { return avatars; }
    public List<String> sleeves() { return sleeves; }
    public List<Pet> pets() { return pets; }
    public List<String> phrases() { return phrases; }
    public List<String> stickers() { return stickers; }
    public List<Title> titles() { return titles; }

    public boolean isDefaultAvatar(String id) { return defaultAvatars.contains(id); }
    public boolean isDefaultSleeve(String id) { return Profile.DEFAULT_SLEEVE.equals(id); }
    public boolean isDefaultEmote(String id) { return Profile.DEFAULT_EMOTES.contains(id); }
    public boolean isSticker(String id) { return id != null && id.startsWith("Sticker_"); }

    public boolean hasAvatar(String id) { return avatars.contains(id); }
    public boolean hasSleeve(String id) { return sleeves.contains(id); }
    public boolean hasPet(String id) { return pets.stream().anyMatch(p -> p.id().equals(id)); }
    public boolean hasEmote(String id) { return phrases.contains(id) || stickers.contains(id); }
    public boolean hasTitle(String id) { return titles.stream().anyMatch(t -> t.id().equals(id)); }

    /** Stable fingerprint of the catalogue content: the static-content hash the client caches the list under. */
    public int hash() {
        return java.util.Objects.hash(avatars, sleeves, pets, phrases, stickers, titles) & 0x7fffffff;
    }

    public String summary() {
        return avatars.size() + " avatars, " + sleeves.size() + " sleeves, " + pets.size() + " pets, " + phrases.size() + " phrases, "
                + stickers.size() + " stickers, " + titles.size() + " titles";
    }

    /** The minimum a client can work with when the install's data cannot be read: the defaults only. */
    public static CosmeticCatalog minimal() {
        CosmeticCatalog c = new CosmeticCatalog();
        c.avatars.addAll(DEFAULT_AVATARS);
        c.defaultAvatars.addAll(DEFAULT_AVATARS);
        c.sleeves.add(Profile.DEFAULT_SLEEVE);
        c.phrases.addAll(Profile.DEFAULT_EMOTES);
        return c;
    }

    /** Read the catalogue from the client folder ({@code <client>/MTGA_Data/Downloads}); never throws, falls back to {@link #minimal()} parts. */
    public static CosmeticCatalog load(File clientDir) {
        CosmeticCatalog c = minimal();
        File downloads = new File(clientDir, "MTGA_Data/Downloads");
        try {
            Alt avatar = Alt.open(downloads, "ALT_Avatar_");
            if (avatar != null) {
                Set<String> thumb = new TreeSet<>(avatar.keys("ALT_Avatar.ThumbnailPayload", "Cosmetic_AvatarId"));
                Set<String> bust = new TreeSet<>(avatar.keys("ALT_Avatar.BustPayload", "Cosmetic_AvatarId"));
                thumb.retainAll(bust);      // drawn in the picker and in the match: both images must exist
                thumb.removeIf(id -> !id.startsWith("Avatar_Basic_"));      // NPE_BOLAS...: tutorial opponents, not for players
                if (!thumb.isEmpty()) { c.avatars.clear(); c.avatars.addAll(thumb); }
                c.defaultAvatars.clear();
                for (String d : DEFAULT_AVATARS) if (thumb.contains(d)) c.defaultAvatars.add(d);
                if (c.defaultAvatars.isEmpty() && !c.avatars.isEmpty()) c.defaultAvatars.add(c.avatars.get(0));
            }
            Alt card = Alt.open(downloads, "ALT_Card_");
            if (card != null) {
                Set<String> s = new TreeSet<>(card.keys("ALT_Card.Sleeve", "Cosmetic_SleeveId"));
                s.remove(Profile.DEFAULT_SLEEVE);
                c.sleeves.clear();
                c.sleeves.add(Profile.DEFAULT_SLEEVE);
                c.sleeves.addAll(s);
            }
            Alt cosmetic = Alt.open(downloads, "ALT_Cosmetic_");
            if (cosmetic != null) c.pets.addAll(cosmetic.pets("ALT_Cosmetic.PetPayload"));
            Alt emotes = Alt.open(downloads, "ALT_Emotes_");
            if (emotes != null) {
                Set<String> p = new TreeSet<>(emotes.keys("ALT_Emotes.EmoteLocKey", "Emote_IdActual"));
                if (!p.isEmpty()) { c.phrases.clear(); c.phrases.addAll(p); }
            }
            Alt prefab = Alt.open(downloads, "ALT_Prefab_");
            if (prefab != null) {
                for (String id : new TreeSet<>(prefab.keys("ALT_Prefab.EmoteViewPrefab", "Emote_Id"))) if (id.startsWith("Sticker_")) c.stickers.add(id);
            }
        } catch (Exception e) {
            System.out.println("[mtga] cosmetics: cannot read the asset lookup trees: " + e);
        }
        c.titles.addAll(readTitles(downloads));
        return c;
    }

    /** Titles are localisation keys {@code Titles/<Group>/<Name>} of the client ({@code _RewardKey} variants are popup texts). */
    private static List<Title> readTitles(File downloads) {
        List<Title> out = new ArrayList<>();
        File[] files = new File(downloads, "Raw").listFiles((d, n) -> n.startsWith("Raw_ClientLocalization_") && n.endsWith(".mtga"));
        if (files == null || files.length == 0) return out;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + files[0].getAbsolutePath()); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("select Key from Loc where Key like 'Titles/%' and Key not like '%\\_RewardKey%' escape '\\' "
                     + "and Key <> 'Titles/Core/NoTitle' and enUS is not null and enUS <> '' order by Key")) {
            while (rs.next()) {
                String key = rs.getString(1);
                out.add(new Title("Title_" + key.substring("Titles/".length()).replace('/', '_'), key));
            }
        } catch (Exception e) {
            System.out.println("[mtga] cosmetics: cannot read the titles: " + e);
        }
        return out;
    }

    /** One {@code ALT_*.mtga} file: trees of nodes (extractors, evaluators, payloads) and their connections. */
    private static final class Alt {
        private final JsonObject trees;

        private Alt(JsonObject trees) { this.trees = trees; }

        static Alt open(File downloads, String prefix) throws Exception {
            File[] files = new File(downloads, "ALT").listFiles((d, n) -> n.startsWith(prefix) && n.endsWith(".mtga"));
            if (files == null || files.length == 0) return null;
            File newest = files[0];
            for (File f : files) if (f.lastModified() > newest.lastModified()) newest = f;
            return new Alt(JsonParser.parseString(Files.readString(newest.toPath(), StandardCharsets.UTF_8)).getAsJsonObject());
        }

        private JsonObject tree(String name) {
            return trees.has(name) && trees.get(name).isJsonObject() ? trees.getAsJsonObject(name) : null;
        }

        /** Extractor node id -> its type, for the nodes of one tree. */
        private static Map<String, String> extractors(JsonObject tree) {
            Map<String, String> m = new HashMap<>();
            for (JsonElement n : tree.getAsJsonArray("Nodes")) {
                JsonObject o = n.getAsJsonObject();
                if (o.has("ExtractorType")) m.put(o.get("NodeId").getAsString(), o.get("ExtractorType").getAsString());
            }
            return m;
        }

        private static Map<String, JsonElement> connections(JsonObject tree) {
            Map<String, JsonElement> m = new HashMap<>();
            for (JsonElement c : tree.getAsJsonArray("Connections")) {
                JsonObject o = c.getAsJsonObject();
                m.put(o.get("Parent").getAsString(), o.get("Child"));
            }
            return m;
        }

        /** The union of the keys of every extractor node of type {@code ...Extractors.<anything>.<extractor>} in the tree. */
        List<String> keys(String treeName, String extractor) {
            JsonObject t = tree(treeName);
            if (t == null) return Collections.emptyList();
            Map<String, String> ex = extractors(t);
            Map<String, JsonElement> con = connections(t);
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, String> e : ex.entrySet()) {
                if (!e.getValue().endsWith("." + extractor)) continue;
                JsonElement child = con.get(e.getKey());
                if (child != null && child.isJsonObject()) out.addAll(child.getAsJsonObject().keySet());
            }
            out.remove("");
            return new ArrayList<>(out);
        }

        /** Pet payload tree: PetId node (key = pet name) -> below it a PetVariantId node (keys = variants). */
        List<Pet> pets(String treeName) {
            JsonObject t = tree(treeName);
            if (t == null) return Collections.emptyList();
            Map<String, String> ex = extractors(t);
            Map<String, JsonElement> con = connections(t);
            List<Pet> out = new ArrayList<>();
            for (Map.Entry<String, String> e : ex.entrySet()) {
                if (!e.getValue().endsWith(".PetId")) continue;
                JsonElement child = con.get(e.getKey());
                if (child == null || !child.isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> pet : child.getAsJsonObject().entrySet()) {
                    if (pet.getKey().isEmpty()) continue;
                    List<String> variants = variantsBelow(pet.getValue().getAsString(), ex, con);
                    if (variants.isEmpty()) variants = List.of("Level1");
                    for (String v : variants) out.add(new Pet(pet.getKey(), v));
                }
            }
            out.sort(java.util.Comparator.comparing(Pet::name, String.CASE_INSENSITIVE_ORDER).thenComparing(Pet::variant));
            return out;
        }

        private static List<String> variantsBelow(String start, Map<String, String> ex, Map<String, JsonElement> con) {
            ArrayDeque<String> todo = new ArrayDeque<>(List.of(start));
            Set<String> seen = new java.util.HashSet<>();
            while (!todo.isEmpty()) {
                String id = todo.poll();
                if (!seen.add(id)) continue;
                JsonElement child = con.get(id);
                if (ex.containsKey(id) && ex.get(id).endsWith(".PetVariantId") && child != null && child.isJsonObject()) {
                    List<String> v = new ArrayList<>(child.getAsJsonObject().keySet());
                    v.remove("");
                    return v;
                }
                if (child == null) continue;
                if (child.isJsonArray()) for (JsonElement e : (JsonArray) child) todo.add(e.getAsString());
                else if (child.isJsonObject()) for (JsonElement e : child.getAsJsonObject().asMap().values()) todo.add(e.getAsString());
            }
            return Collections.emptyList();
        }
    }
}

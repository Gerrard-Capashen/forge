package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.wizards.mtga.fd.CosmeticsClient;
import com.wizards.mtga.fd.PreferredCosmetics;
import forge.mtga.profile.CosmeticCatalog;
import forge.mtga.profile.FileProfileStore;
import forge.mtga.profile.Profile;
import forge.mtga.profile.PublicProfile;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless check of the player profile (no Forge, no client): the file store (stable id, round trip, legacy preferences,
 * unreadable file), the cosmetics API (what the client reads, what it may change, public view, deck overrides) against the
 * catalogue read from a client folder. Args: [clientDir]. Prints "PROFILE CHECK OK" or the failures; exit code 1 on failure.
 */
public final class ProfileCheck {
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures.add(what);
    }

    private static void delete(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        dir.delete();
    }

    public static void main(String[] args) throws Exception {
        File clientDir = new File(args.length > 0 ? args[0] : "../../client");
        File dir = Files.createTempDirectory("profile-check").toFile();

        // ---- store ----
        System.out.println("store:");
        Files.writeString(new File(dir, "preferences.json").toPath(), "{\"PlayBlade\":\"forge-ai\"}", StandardCharsets.UTF_8);
        FileProfileStore store = new FileProfileStore(dir);
        Profile first = store.load();
        check(first.playerId != null && first.playerId.matches("[0-9a-f-]{36}"), "first start makes a GUID player id (" + first.playerId + ")");
        check(new File(dir, "profile.json").isFile(), "profile.json written");
        check("forge-ai".equals(first.preferences.get("PlayBlade")), "preferences.json imported once");
        check(first.tag().matches("\\d{5}") && first.clientName().equals("Forge Player#" + first.tag()), "client name " + first.clientName());
        check(Profile.cleanName("  Al#ice \u0007 ").equals("Al ice") && Profile.cleanName("   ").equals(Profile.DEFAULT_NAME)
                && Profile.cleanName("x".repeat(40)).length() == 24, "names are cleaned");

        store.update(p -> { p.displayName = "Alice"; p.equipped.avatar = "Avatar_Basic_JaceBeleren"; p.preferences.put("k", "v"); });
        Profile again = new FileProfileStore(dir).load();
        check(again.playerId.equals(first.playerId), "player id is stable across restarts");
        check("Alice".equals(again.displayName) && "Avatar_Basic_JaceBeleren".equals(again.equipped.avatar) && "v".equals(again.preferences.get("k")), "changes persist");
        Profile copy = store.load();
        copy.displayName = "Mallory";
        check("Alice".equals(store.load().displayName), "load() hands out a copy");

        File other = Files.createTempDirectory("profile-check-bad").toFile();
        Files.writeString(new File(other, "profile.json").toPath(), "{ not json", StandardCharsets.UTF_8);
        Profile fresh = new FileProfileStore(other).load();
        File[] bad = other.listFiles((d, n) -> n.startsWith("profile.json.bad"));
        check(fresh.playerId != null && bad != null && bad.length == 1, "an unreadable profile is kept aside and replaced");

        // ---- cosmetics API ----
        System.out.println("cosmetics (" + clientDir + "):");
        CosmeticCatalog catalog = CosmeticCatalog.load(clientDir);
        System.out.println("  catalogue: " + catalog.summary());
        ProfileApi api = new ProfileApi(store, catalog);
        boolean real = catalog.avatars().size() > 50;
        check(catalog.hasAvatar(Profile.DEFAULT_AVATAR) && catalog.hasSleeve(Profile.DEFAULT_SLEEVE) && catalog.hasEmote("Phrase_Basic_Hello"), "defaults are in the catalogue");

        JsonObject sc = api.staticContent().getAsJsonObject("AvailableCosmetics").getAsJsonObject("Object");
        check(sc.getAsJsonArray("Avatars").size() == catalog.avatars().size() && sc.getAsJsonArray("Sleeves").size() == catalog.sleeves().size()
                && sc.getAsJsonArray("Emotes").size() == catalog.phrases().size() + catalog.stickers().size()
                && sc.getAsJsonArray("Titles").size() == catalog.titles().size(), "static content lists the whole catalogue");
        JsonObject firstEmote = sc.getAsJsonArray("Emotes").get(0).getAsJsonObject();
        check(firstEmote.has("Page") && firstEmote.has("FlipType") && firstEmote.has("Category") && firstEmote.has("AcquisitionFlags"), "emote entries carry page, flip type, category, flags");
        if (real) check(sc.getAsJsonArray("Emotes").toString().contains("\"Page\":\"Sticker\""), "stickers are on the Sticker page");

        CosmeticsClient owned = api.owned();
        check(owned.getAvatarsCount() == catalog.avatars().size() && owned.getSleevesCount() == catalog.sleeves().size() && owned.getPetsCount() == catalog.pets().size(),
                "owned = everything while owned.all");

        PreferredCosmetics pref = api.preferred();
        check("Avatar_Basic_JaceBeleren".equals(pref.getAvatar().getId()) && pref.getEmotesCount() == 5 && !pref.hasPet() && !pref.hasTitle(), "preferred reflects the profile");

        String avatar = real ? "Avatar_Basic_LilianaVess" : Profile.DEFAULT_AVATAR;
        check(avatar.equals(api.setAvatar(avatar).getAvatar().getId()) && avatar.equals(store.load().equipped.avatar), "set avatar is accepted and persisted");
        check(avatar.equals(api.setAvatar("Avatar_Does_Not_Exist").getAvatar().getId()), "an unknown avatar is refused (selection unchanged)");
        if (real) {
            String sleeve = catalog.sleeves().get(5);
            check(sleeve.equals(api.setSleeve(sleeve).getSleeve().getId()), "set sleeve " + sleeve);
            CosmeticCatalog.Pet pet = catalog.pets().get(3);
            PreferredCosmetics withPet = api.setPet(pet.id());
            check(pet.id().equals(withPet.getPet().getId()) && pet.name().equals(withPet.getPet().getName()) && pet.variant().equals(withPet.getPet().getVariant()), "set pet " + pet.id());
            check(!api.setPet("").hasPet(), "empty pet id removes the pet");
            CosmeticCatalog.Title t = catalog.titles().get(0);
            check(t.id().equals(api.setTitle(t.id()).getTitle().getId()) && !api.setTitle("NoTitle").hasTitle(), "set title and clear it with NoTitle");
            List<String> many = new ArrayList<>(catalog.phrases().subList(0, 20));
            many.addAll(catalog.stickers().subList(0, 12));
            many.add(catalog.phrases().get(0));
            many.add("Phrase_Nope");
            PreferredCosmetics em = api.setEmotes(many);
            long stickers = em.getEmotesList().stream().filter(e -> e.getId().startsWith("Sticker_")).count();
            check(em.getEmotesCount() == 25 && stickers == 10, "emotes: owned only, no duplicates, 15 phrases + 10 stickers at most (" + em.getEmotesCount() + ")");
        }

        // ownership online (mastery, docs/results-rank-mastery.md section 3): the always-owned set + the coordinator's grants
        if (real) {
            System.out.println("mastery ownership:");
            String sleeve = catalog.sleeves().stream().filter(x -> !x.equals(Profile.DEFAULT_SLEEVE)).findFirst().orElseThrow();
            String otherSleeve = catalog.sleeves().stream().filter(x -> !x.equals(Profile.DEFAULT_SLEEVE) && !x.equals(sleeve)).findFirst().orElseThrow();
            String title = catalog.titles().stream().map(CosmeticCatalog.Title::id).filter(x -> !x.equals(forge.mtga.profile.Mastery.CHAMPION_TITLE)).findFirst().orElseThrow();
            String sticker = catalog.stickers().get(0);
            String granted = catalog.avatars().stream().filter(x -> !x.equals(forge.mtga.profile.Mastery.DEFAULT_AVATAR) && !x.equals("Avatar_Basic_JaceBeleren")).findFirst().orElseThrow();
            store.update(p -> {
                p.owned.all = false;
                p.owned.avatars = new ArrayList<>(List.of(granted));
                p.owned.sleeves = new ArrayList<>(List.of(sleeve));
                p.owned.pets = new ArrayList<>();
                p.owned.emotes = new ArrayList<>(List.of(sticker));
                p.owned.titles = new ArrayList<>(List.of(title));
                p.equipped.avatar = "Avatar_Basic_JaceBeleren";       // equipped while everything was unlocked, not owned now
                p.equipped.sleeve = otherSleeve;
                p.equipped.title = forge.mtga.profile.Mastery.CHAMPION_TITLE;     // equipped while everything was unlocked
            });
            CosmeticsClient own = api.owned();
            check(own.getAvatarsCount() == 2 && own.getAvatars(0).getId().equals(forge.mtga.profile.Mastery.DEFAULT_AVATAR)
                    && own.getAvatars(1).getId().equals(granted), "owned avatars = the Adventurer + the granted one (" + own.getAvatarsCount() + ")");
            check(own.getSleevesCount() == 2 && own.getPetsCount() == 0 && own.getEmotesCount() == Profile.DEFAULT_EMOTES.size() + 1,
                    "owned sleeves = default + granted, no pet, emotes = the basic phrases + the granted sticker");
            check(own.getTitlesCount() == 1 && own.getTitles(0).getId().equals(title), "owned titles = the granted one only (the champion title is earned last)");
            check(forge.mtga.profile.Mastery.DEFAULT_AVATAR.equals(api.self().avatar()) && Profile.DEFAULT_SLEEVE.equals(api.self().sleeve())
                    && forge.mtga.profile.Mastery.DEFAULT_AVATAR.equals(api.preferred().getAvatar().getId()) && api.self().title().isEmpty(),
                    "an equipped cosmetic that is not owned shows as the default (no title)");
            check(granted.equals(api.setAvatar(granted).getAvatar().getId()), "an owned avatar can be equipped");
            check(granted.equals(api.setAvatar("Avatar_Basic_JaceBeleren").getAvatar().getId()), "an avatar that is not owned is refused");
            check(Profile.DEFAULT_SLEEVE.equals(api.setSleeve(otherSleeve).getSleeve().getId()) && sleeve.equals(api.setSleeve(sleeve).getSleeve().getId()),
                    "a sleeve that is not owned is refused, an owned one accepted");
            check(!api.setPet(catalog.pets().get(0).id()).hasPet(), "a pet that is not owned is refused");
            check(title.equals(api.setTitle(title).getTitle().getId()) && title.equals(api.setTitle(forge.mtga.profile.Mastery.CHAMPION_TITLE).getTitle().getId()),
                    "an owned title can be equipped, the champion title (not owned) is refused");
            String phrase = catalog.phrases().stream().filter(x -> !Profile.DEFAULT_EMOTES.contains(x)).findFirst().orElseThrow();
            check(!api.setEmotes(List.of("Phrase_Basic_Hello", phrase, sticker)).getEmotesList().stream().anyMatch(e -> e.getId().equals(phrase)),
                    "an emote that is not owned is left out of the loadout");
            new forge.mtga.social.MasteryService(null, store, dir);
            check(store.load().owned.all, "an offline start (no account) owns everything again");
        }
        store.update(p -> p.owned.all = true);

        // ---- public view / deck overrides ----
        System.out.println("public view:");
        store.update(p -> { p.equipped.emotes = new ArrayList<>(List.of("Phrase_Basic_Hello")); p.equipped.avatar = Profile.DEFAULT_AVATAR; p.equipped.pet = ""; });
        PublicProfile pub = api.self();
        String json = pub.toJson().toString();
        check(!json.contains("preferences") && !json.contains("owned") && !json.contains("created") && !json.contains("updated"), "the public profile carries no private data");
        check(PublicProfile.fromJson(pub.toJson()).equals(pub), "public profile survives its JSON round trip");
        check(PublicProfile.fromJson(new com.google.gson.Gson().fromJson("{\"name\":\"X\"}", JsonObject.class)).avatar().isEmpty(), "a sparse peer profile is read leniently");

        JsonObject summary = new JsonObject();
        JsonObject deckCosmetics = new JsonObject();
        deckCosmetics.addProperty("Avatar", real ? "Avatar_Basic_VivienReid" : "");
        deckCosmetics.addProperty("Sleeve", "");
        deckCosmetics.addProperty("Pet", "Not.Owned");
        deckCosmetics.addProperty("Title", "");
        deckCosmetics.add("Emotes", new JsonArray());
        summary.add("PreferredCosmetics", deckCosmetics);
        PublicProfile forDeck = api.forDeck(summary);
        check((real ? "Avatar_Basic_VivienReid" : Profile.DEFAULT_AVATAR).equals(forDeck.avatar()) && forDeck.pet().isEmpty()
                && forDeck.emotes().equals(pub.emotes()) && forDeck.sleeve().equals(pub.sleeve()), "deck cosmetics override the profile where set and owned");

        PublicProfile stranger = new PublicProfile("x", "Eve", "Avatar_From_A_Newer_Build", "CardBack_Unknown", "Nope.Level9", "Title_Nope",
                List.of("Phrase_Basic_Hello", "Phrase_Unknown"), "Gold", 2);
        PublicProfile seen = api.sanitize(stranger);
        check(seen.avatar().isEmpty() && seen.sleeve().equals(Profile.DEFAULT_SLEEVE) && seen.pet().isEmpty() && seen.title().isEmpty()
                && seen.emotes().equals(List.of("Phrase_Basic_Hello")) && seen.rankClass().equals("Gold"), "an opponent's unknown cosmetics are dropped, the rest kept");

        JsonObject sel = api.selectionJson(new PublicProfile("id", "Bob", "Avatar_Basic_JaceBeleren", "CardBack_Default", "AFR_Dragon.Skin1", "Title_X", List.of("Phrase_Basic_Hello"), "Bronze", 4));
        check("Avatar_Basic_JaceBeleren".equals(sel.getAsJsonObject("Avatar").get("Id").getAsString()) && "AFR_Dragon".equals(sel.getAsJsonObject("Pet").get("Name").getAsString())
                && "Skin1".equals(sel.getAsJsonObject("Pet").get("Variant").getAsString()) && sel.getAsJsonArray("Emotes").size() == 1, "MatchCreated selection JSON");

        delete(dir);
        delete(other);
        System.out.println(failures.isEmpty() ? "PROFILE CHECK OK" : "PROFILE CHECK FAILED " + failures.size() + ": " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}

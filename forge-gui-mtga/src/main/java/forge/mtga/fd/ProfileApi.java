package forge.mtga.fd;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.wizards.mtga.fd.CosmeticEmoteEntry;
import com.wizards.mtga.fd.CosmeticPetEntry;
import com.wizards.mtga.fd.CosmeticTitleEntry;
import com.wizards.mtga.fd.CosmeticsAvatarEntry;
import com.wizards.mtga.fd.CosmeticsClient;
import com.wizards.mtga.fd.CosmeticsSleeveEntry;
import com.wizards.mtga.fd.PreferredCosmetics;
import forge.mtga.profile.BotNames;
import forge.mtga.profile.CosmeticCatalog;
import forge.mtga.profile.Mastery;
import forge.mtga.profile.Profile;
import forge.mtga.profile.ProfileStore;
import forge.mtga.profile.PublicProfile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static forge.mtga.fd.FrontDoor.obj;
import static forge.mtga.fd.FrontDoor.str;

/**
 * The Front Door's view of the player profile: turns the {@link Profile} and the {@link CosmeticCatalog} into the messages the
 * client reads (static content catalogue, owned cosmetics, preferred cosmetics, the per-player block of {@code MatchCreated})
 * and applies the client's changes (set avatar, sleeve, pet, title, emotes) to the {@link ProfileStore}.
 * Protocol specific; the profile model itself knows nothing of the wire.
 */
public final class ProfileApi {
    // AcquisitionFlags of the client
    static final int FLAG_DEFAULT = 1, FLAG_EVENT = 2, FLAG_ACHIEVEMENTS = 0x40;
    static final int MAX_PHRASES = 15, MAX_STICKERS = 10;

    private final ProfileStore store;
    private final CosmeticCatalog catalog;
    /** InventoryInfo.SeqId: the client applies an owned-cosmetics list only when this grows. */
    private final AtomicInteger inventorySeq = new AtomicInteger(1);

    public ProfileApi(ProfileStore store, CosmeticCatalog catalog) {
        this.store = store;
        this.catalog = catalog;
    }

    public ProfileStore store() { return store; }
    public CosmeticCatalog catalog() { return catalog; }
    public Profile profile() { return store.load(); }
    /** The player as others see them: what is equipped, where owned (an online profile shows only what it owns; see {@link #wearable}). */
    public PublicProfile self() { return wearable(store.load()); }
    /** Developer flag (orange name), as the coordinator last said. */
    public boolean isDev() { return store.load().wotc; }
    int inventorySeq() { return inventorySeq.incrementAndGet(); }

    // ---- ownership ----------------------------------------------------------------------------------------------
    // owned.all (offline): the whole catalogue. Otherwise (online, docs/results-rank-mastery.md section 3) the always-owned set
    // (Mastery.ALWAYS_OWNED: the Adventurer, the default card back, the basic phrases) plus the coordinator's grants.

    private List<String> ownedAvatars(Profile p) {
        return p.owned.all ? catalog.avatars() : merge(List.of(Mastery.DEFAULT_AVATAR), p.owned.avatars, catalog::hasAvatar);
    }

    private List<String> ownedSleeves(Profile p) {
        return p.owned.all ? catalog.sleeves() : merge(List.of(Profile.DEFAULT_SLEEVE), p.owned.sleeves, catalog::hasSleeve);
    }

    private List<CosmeticCatalog.Pet> ownedPets(Profile p) {
        return p.owned.all ? catalog.pets() : catalog.pets().stream().filter(x -> p.owned.pets.contains(x.id())).toList();
    }

    private List<String> ownedEmotes(Profile p) {
        if (p.owned.all) {
            List<String> all = new ArrayList<>(catalog.phrases());
            all.addAll(catalog.stickers());
            return all;
        }
        return merge(Profile.DEFAULT_EMOTES, p.owned.emotes, catalog::hasEmote);
    }

    private List<CosmeticCatalog.Title> ownedTitles(Profile p) {
        return p.owned.all ? catalog.titles() : catalog.titles().stream().filter(x -> p.owned.titles.contains(x.id())).toList();
    }

    /**
     * The equipped cosmetics, each replaced by the default when it is not owned (a profile equipped offline with everything unlocked,
     * then started online): the Adventurer, the default card back, no pet, no title, the owned emotes (the basic ones if none is left).
     */
    PublicProfile wearable(Profile p) {
        PublicProfile all = PublicProfile.of(p);
        if (p.owned.all) return all;
        String avatar = ownedAvatars(p).contains(all.avatar()) ? all.avatar() : Mastery.DEFAULT_AVATAR;
        String sleeve = ownedSleeves(p).contains(all.sleeve()) ? all.sleeve() : Profile.DEFAULT_SLEEVE;
        String pet = all.pet().isEmpty() || ownedPets(p).stream().anyMatch(x -> x.id().equals(all.pet())) ? all.pet() : "";
        String title = all.title().isEmpty() || ownedTitles(p).stream().anyMatch(x -> x.id().equals(all.title())) ? all.title() : "";
        List<String> owned = ownedEmotes(p);
        List<String> emotes = new ArrayList<>(all.emotes());
        emotes.removeIf(e -> !owned.contains(e));
        if (emotes.isEmpty()) emotes = Profile.DEFAULT_EMOTES;
        return new PublicProfile(all.playerId(), all.name(), avatar, sleeve, pet, title, emotes, all.rankClass(), all.rankTier());
    }

    private static List<String> merge(List<String> defaults, List<String> granted, java.util.function.Predicate<String> exists) {
        Set<String> out = new LinkedHashSet<>(defaults);
        for (String g : granted) if (exists.test(g)) out.add(g);
        return new ArrayList<>(out);
    }

    // ---- messages the client reads ------------------------------------------------------------------------------

    /** StaticContent: the catalogue of everything that exists ({@code AvailableCosmetics}, hashed so the client may cache it). */
    JsonObject staticContent() {
        JsonArray avatars = new JsonArray(), sleeves = new JsonArray(), pets = new JsonArray(), emotes = new JsonArray(), titles = new JsonArray();
        for (String id : catalog.avatars()) avatars.add(obj("Id", id, "AcquisitionFlags", flagName(catalog.isDefaultAvatar(id))));
        for (String id : catalog.sleeves()) sleeves.add(obj("Id", id, "AcquisitionFlags", flagName(catalog.isDefaultSleeve(id))));
        for (CosmeticCatalog.Pet x : catalog.pets()) pets.add(obj("Id", x.id(), "Name", x.name(), "Variant", x.variant(), "AcquisitionFlags", "Event"));
        for (String id : catalog.phrases()) emotes.add(emoteJson(id));
        for (String id : catalog.stickers()) emotes.add(emoteJson(id));
        // a title is listed to the player while it is owned or has an acquisition source (TitleSelectPanel)
        for (CosmeticCatalog.Title t : catalog.titles()) titles.add(obj("Id", t.id(), "InternalName", t.id(), "LocKey", t.locKey(), "AcquisitionFlags", "Achievements"));
        JsonObject all = obj("ArtStyles", new JsonArray(), "Avatars", avatars, "Pets", pets, "Sleeves", sleeves, "Emotes", emotes, "Titles", titles);
        return obj("AvailableCosmetics", obj("Hash", catalog.hash(), "Object", all));
    }

    private JsonObject emoteJson(String id) {
        boolean sticker = catalog.isSticker(id);
        return obj("Id", id, "Page", sticker ? "Sticker" : "Phrase", "FlipType", "None", "Category", emoteCategory(id), "Treatment", "",
                "AcquisitionFlags", flagName(catalog.isDefaultEmote(id)));
    }

    /** {@code Phrase_FRA_Ajani_DuelAdmirably} -> {@code FRA}. */
    static String emoteCategory(String id) {
        String[] parts = id.split("_");
        return parts.length > 2 ? parts[1] : "Basic";
    }

    private static String flagName(boolean isDefault) { return isDefault ? "DefaultLoginGrant" : "Event"; }

    /** InventoryInfo.Cosmetics / GetPlayerOwnedCosmetics: what the player owns. */
    CosmeticsClient owned() {
        Profile p = store.load();
        CosmeticsClient.Builder b = CosmeticsClient.newBuilder();
        for (String id : ownedAvatars(p)) b.addAvatars(CosmeticsAvatarEntry.newBuilder().setId(id).setAcquisitionFlagsInternal(flags(catalog.isDefaultAvatar(id))));
        for (String id : ownedSleeves(p)) b.addSleeves(CosmeticsSleeveEntry.newBuilder().setId(id).setAcquisitionFlagsInternal(flags(catalog.isDefaultSleeve(id))));
        for (CosmeticCatalog.Pet x : ownedPets(p)) b.addPets(CosmeticPetEntry.newBuilder().setId(x.id()).setName(x.name()).setVariant(x.variant()).setAcquisitionFlagsInternal(FLAG_EVENT));
        for (String id : ownedEmotes(p)) b.addEmotes(CosmeticEmoteEntry.newBuilder().setId(id).setPageInternal(catalog.isSticker(id) ? 1 : 0)
                .setCategory(emoteCategory(id)).setAcquisitionFlagsInternal(flags(catalog.isDefaultEmote(id))));
        for (CosmeticCatalog.Title t : ownedTitles(p)) b.addTitles(CosmeticTitleEntry.newBuilder().setId(t.id()).setInternalName(t.id()).setLocKey(t.locKey()).setAcquisitionFlagsInternal(FLAG_ACHIEVEMENTS));
        return b.build();
    }

    private static int flags(boolean isDefault) { return isDefault ? FLAG_DEFAULT : FLAG_EVENT; }

    /** The equipped cosmetics (StartHook, GetPreferredCosmetics and the answer of every Set*). */
    PreferredCosmetics preferred() { return preferred(self()); }

    PreferredCosmetics preferred(PublicProfile p) {
        PreferredCosmetics.Builder b = PreferredCosmetics.newBuilder();
        if (!p.avatar().isEmpty()) b.setAvatar(CosmeticsAvatarEntry.newBuilder().setId(p.avatar()));
        if (!p.sleeve().isEmpty()) b.setSleeve(CosmeticsSleeveEntry.newBuilder().setId(p.sleeve()));
        if (!p.pet().isEmpty()) {
            String[] nv = splitPet(p.pet());
            b.setPet(CosmeticPetEntry.newBuilder().setId(p.pet()).setName(nv[0]).setVariant(nv[1]));
        }
        if (!p.title().isEmpty()) b.setTitle(CosmeticTitleEntry.newBuilder().setId(p.title()));
        for (String e : p.emotes()) b.addEmotes(CosmeticEmoteEntry.newBuilder().setId(e));
        return b.build();
    }

    /** {@code "Name.Variant"} -> {name, variant}. */
    static String[] splitPet(String id) {
        int dot = id.indexOf('.');
        return dot < 0 ? new String[] {id, ""} : new String[] {id.substring(0, dot), id.substring(dot + 1)};
    }

    /** {@code CosmeticsSelection} of a MatchCreated player: the JSON form of {@link PreferredCosmetics}. */
    JsonObject selectionJson(PublicProfile p) {
        JsonObject o = new JsonObject();
        if (!p.avatar().isEmpty()) o.add("Avatar", obj("Id", p.avatar()));
        if (!p.sleeve().isEmpty()) o.add("Sleeve", obj("Id", p.sleeve()));
        if (!p.pet().isEmpty()) {
            String[] nv = splitPet(p.pet());
            o.add("Pet", obj("Id", p.pet(), "Name", nv[0], "Variant", nv[1]));
        }
        if (!p.title().isEmpty()) o.add("Title", obj("Id", p.title()));
        JsonArray emotes = new JsonArray();
        for (String e : p.emotes()) emotes.add(obj("Id", e));
        o.add("Emotes", emotes);
        return o;
    }

    // ---- the client's changes -----------------------------------------------------------------------------------

    PreferredCosmetics setAvatar(String id) {
        return change("avatar", id, p -> p.equipped.avatar = id, p -> catalog.hasAvatar(id) && ownedAvatars(p).contains(id));
    }

    PreferredCosmetics setSleeve(String id) {
        return change("sleeve", id, p -> p.equipped.sleeve = id, p -> catalog.hasSleeve(id) && ownedSleeves(p).contains(id));
    }

    /** Empty id = no pet. */
    PreferredCosmetics setPet(String id) {
        return change("pet", id, p -> p.equipped.pet = id, p -> id.isEmpty() || ownedPets(p).stream().anyMatch(x -> x.id().equals(id)));
    }

    /** Empty id or {@code NoTitle} = no title. */
    PreferredCosmetics setTitle(String requested) {
        String id = "NoTitle".equals(requested) ? "" : requested;
        return change("title", id, p -> p.equipped.title = id, p -> id.isEmpty() || ownedTitles(p).stream().anyMatch(x -> x.id().equals(id)));
    }

    /** The emote loadout, in the order given: owned emotes only, at most 15 phrases and 10 stickers. */
    PreferredCosmetics setEmotes(List<String> ids) {
        store.update(p -> {
            List<String> owned = ownedEmotes(p);
            List<String> kept = new ArrayList<>();
            int phrases = 0, stickers = 0;
            for (String id : ids) {
                if (kept.contains(id) || !owned.contains(id)) continue;
                if (catalog.isSticker(id) ? ++stickers > MAX_STICKERS : ++phrases > MAX_PHRASES) continue;
                kept.add(id);
            }
            p.equipped.emotes = kept;
        });
        System.out.println("[mtga] profile: emotes now " + store.load().equipped.emotes.size());
        return preferred();
    }

    private PreferredCosmetics change(String what, String id, java.util.function.Consumer<Profile> apply, java.util.function.Predicate<Profile> allowed) {
        Profile now = store.load();
        if (id == null || !allowed.test(now)) {
            System.out.println("[mtga] profile: refused " + what + " '" + id + "' (unknown or not owned)");
            return preferred();
        }
        store.update(apply);
        System.out.println("[mtga] profile: " + what + " -> " + (id.isEmpty() ? "(none)" : id));
        return preferred();
    }

    // ---- matches ------------------------------------------------------------------------------------------------

    /** The player as the client should see them in a match: the profile with the deck's own cosmetics laid over it. */
    PublicProfile forDeck(JsonObject deckSummary) {
        PublicProfile me = self();
        JsonElement pc = deckSummary == null ? null : deckSummary.get("PreferredCosmetics");
        if (pc == null || !pc.isJsonObject()) return me;
        JsonObject o = pc.getAsJsonObject();
        List<String> emotes = new ArrayList<>();
        JsonElement em = o.get("Emotes");
        if (em != null && em.isJsonArray()) for (JsonElement e : em.getAsJsonArray()) emotes.add(e.getAsString());
        Profile p = store.load();
        String avatar = ownedIf(str(o, "Avatar"), ownedAvatars(p));
        String sleeve = ownedIf(str(o, "Sleeve"), ownedSleeves(p));
        String pet = ownedPets(p).stream().anyMatch(x -> x.id().equals(str(o, "Pet"))) ? str(o, "Pet") : "";
        String title = ownedTitles(p).stream().anyMatch(x -> x.id().equals(str(o, "Title"))) ? str(o, "Title") : "";
        List<String> ownedEmotes = ownedEmotes(p);
        emotes.removeIf(e -> !ownedEmotes.contains(e));
        return me.withDeck(avatar, sleeve, pet, title, emotes);
    }

    private static String ownedIf(String id, List<String> owned) { return id != null && owned.contains(id) ? id : ""; }

    private static final java.util.Random BOT_RND = new java.util.Random();
    private static final String[] BOT_CLASSES = {"Bronze", "Silver", "Silver", "Gold", "Gold", "Gold", "Platinum", "Platinum", "Diamond", "Mythic"};

    /**
     * An AI opponent that reads like a person: a screen name, any avatar and sleeve of the catalogue (an AI shows what it likes,
     * whether or not a player could own it), a rank of the usual spread, the classic emotes (it never uses them).
     */
    PublicProfile forAi() {
        java.util.Random r = BOT_RND;
        List<String> avatars = catalog.avatars(), sleeves = catalog.sleeves();
        String avatar = avatars.isEmpty() ? Profile.DEFAULT_AVATAR : avatars.get(r.nextInt(avatars.size()));
        String sleeve = sleeves.isEmpty() ? Profile.DEFAULT_SLEEVE : sleeves.get(r.nextInt(sleeves.size()));
        String rankClass = BOT_CLASSES[r.nextInt(BOT_CLASSES.length)];
        return new PublicProfile("forge-ai", BotNames.next(r), avatar, sleeve, "", "", Profile.DEFAULT_EMOTES, rankClass, 1 + r.nextInt(4));
    }

    /** An AI opponent with a given label (tests, logs). */
    PublicProfile forAi(String name) {
        PublicProfile p = forAi();
        return new PublicProfile(p.playerId(), name, p.avatar(), p.sleeve(), "", "", Profile.DEFAULT_EMOTES, p.rankClass(), p.rankTier());
    }

    /**
     * An opponent's public profile checked against this client's catalogue: anything this client cannot draw is dropped (the
     * client replaces an unknown avatar by a random one on its own; an unknown sleeve or pet would show nothing or fail).
     */
    PublicProfile sanitize(PublicProfile theirs) {
        String avatar = catalog.hasAvatar(theirs.avatar()) ? theirs.avatar() : "";
        String sleeve = catalog.hasSleeve(theirs.sleeve()) ? theirs.sleeve() : Profile.DEFAULT_SLEEVE;
        String pet = catalog.hasPet(theirs.pet()) ? theirs.pet() : "";
        String title = catalog.hasTitle(theirs.title()) ? theirs.title() : "";
        List<String> emotes = new ArrayList<>(theirs.emotes());
        emotes.removeIf(e -> !catalog.hasEmote(e));
        return new PublicProfile(theirs.playerId(), theirs.name(), avatar, sleeve, pet, title, emotes, theirs.rankClass(), theirs.rankTier());
    }
}

package forge.mtga.profile;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The part of a profile an opponent gets to see: what the other client needs to draw this player (name, avatar, sleeve,
 * pet, title, emotes, rank) plus the stable player id. Never contains owned items, preferences or timestamps. It travels in
 * the peer HELLO / HELLO_OK and is what a web service returns for "show me this player".
 */
public record PublicProfile(String playerId, String name, String avatar, String sleeve, String pet, String title,
                            List<String> emotes, String rankClass, int rankTier) {
    private static final Gson GSON = new Gson();

    public PublicProfile {
        playerId = playerId == null ? "" : playerId;
        name = Profile.cleanName(name, 64);      // a peer's player name is 24 at most (Profile.displayName); an AI label is longer
        avatar = avatar == null ? "" : avatar;
        sleeve = sleeve == null ? "" : sleeve;
        pet = pet == null ? "" : pet;
        title = title == null ? "" : title;
        emotes = emotes == null ? List.of() : List.copyOf(emotes);
        rankClass = rankClass == null || rankClass.isBlank() ? "Bronze" : rankClass;
    }

    public static PublicProfile of(Profile p) {
        return new PublicProfile(p.playerId, p.displayName, p.equipped.avatar, p.equipped.sleeve, p.equipped.pet, p.equipped.title,
                p.equipped.emotes, p.rank.rankClass, p.rank.tier);
    }

    /** Deck-level cosmetics replace the profile's where the deck sets one (empty = use the profile's). */
    public PublicProfile withDeck(String deckAvatar, String deckSleeve, String deckPet, String deckTitle, List<String> deckEmotes) {
        return new PublicProfile(playerId, name, pick(deckAvatar, avatar), pick(deckSleeve, sleeve), pick(deckPet, pet),
                pick(deckTitle, title), deckEmotes != null && !deckEmotes.isEmpty() ? deckEmotes : emotes, rankClass, rankTier);
    }

    private static String pick(String override, String base) { return override == null || override.isBlank() ? base : override; }

    public JsonObject toJson() { return GSON.toJsonTree(this).getAsJsonObject(); }

    /** Lenient reader for what a peer sent: missing fields become empty, never an exception for a well-formed object. */
    public static PublicProfile fromJson(JsonObject o) {
        if (o == null) return null;
        List<String> emotes = new ArrayList<>();
        if (o.has("emotes") && o.get("emotes").isJsonArray()) o.getAsJsonArray("emotes").forEach(e -> emotes.add(e.getAsString()));
        return new PublicProfile(s(o, "playerId"), s(o, "name"), s(o, "avatar"), s(o, "sleeve"), s(o, "pet"), s(o, "title"), emotes,
                s(o, "rankClass"), o.has("rankTier") && !o.get("rankTier").isJsonNull() ? o.get("rankTier").getAsInt() : 4);
    }

    private static String s(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}

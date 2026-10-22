package forge.mtga.profile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * One player's profile: a single versioned JSON document ({@code data/profile.json} for the file store, one record per
 * account for a web service). Decks are NOT part of it (own documents, {@code data/decks}).
 *
 * <p>PUBLIC part (shared with an opponent, see {@link PublicProfile}): playerId, displayName, equipped cosmetics, rank.
 * PRIVATE part: owned cosmetics, preferences, timestamps. Cosmetic ids are the client's own ids
 * ({@code Avatar_Basic_*}, {@code CardBack_*}, {@code <Pet>.<Variant>}, {@code Phrase_*}/{@code Sticker_*}, {@code Title_*}).
 */
public final class Profile {
    public static final int VERSION = 1;
    public static final String DEFAULT_NAME = "Forge Player";
    public static final String DEFAULT_AVATAR = "Avatar_Basic_AjaniGoldmane";
    public static final String DEFAULT_SLEEVE = "CardBack_Default";
    /** Emotes every account starts with (the client's own default order). */
    public static final List<String> DEFAULT_EMOTES = List.of("Phrase_Basic_Hello", "Phrase_Basic_Nice_Thanks",
            "Phrase_Basic_Thinking_YourGo", "Phrase_Basic_Oops_Sorry", "Phrase_Basic_GoodGame");

    public int version = VERSION;
    /** Stable unique id of this account (a random GUID made on first start). */
    public String playerId;
    /** Name without the {@code #12345} tag. */
    public String displayName = DEFAULT_NAME;
    public Equipped equipped = new Equipped();
    public Owned owned = new Owned();
    public Rank rank = new Rank();
    /** The client's player preferences (play blade selection...): a string map. */
    public Map<String, String> preferences = new TreeMap<>();
    public String created;
    public String updated;

    public static final class Equipped {
        public String avatar = DEFAULT_AVATAR;
        public String sleeve = DEFAULT_SLEEVE;
        /** {@code <Name>.<Variant>} or empty for no pet. */
        public String pet = "";
        /** Title id or empty for none. */
        public String title = "";
        public List<String> emotes = new ArrayList<>(DEFAULT_EMOTES);
    }

    /** {@code all}: the player owns everything in the client's catalogue (the offline default, like the card collection). */
    public static final class Owned {
        public boolean all = true;
        public List<String> avatars = new ArrayList<>();
        public List<String> sleeves = new ArrayList<>();
        public List<String> pets = new ArrayList<>();
        public List<String> emotes = new ArrayList<>();
        public List<String> titles = new ArrayList<>();
    }

    /** Display rank only; a web service computes it from per-format ratings. */
    public static final class Rank {
        /** Spark, Bronze, Silver, Gold, Platinum, Diamond, Master, Mythic. */
        public String rankClass = "Bronze";
        public int tier = 4;
    }

    /** The five-digit tag the client shows after the name ({@code Name#00042}); stored so every reader (the client bridge too) sees the same one. */
    public String tag;

    /** Derived from the player id; {@link FileProfileStore} stores it on creation. */
    public String tag() {
        if (tag != null && !tag.isBlank()) return tag;
        int h = playerId == null ? 1 : playerId.hashCode() & 0x7fffffff;
        return String.format("%05d", h % 100000);
    }

    /** What the client calls the account: {@code Name#12345}. */
    public String clientName() { return displayName + "#" + tag(); }

    /** Name as people may type it: no '#', no control characters, trimmed, 24 characters at most. */
    public static String cleanName(String raw) { return cleanName(raw, 24); }

    /** Same with another limit: an opponent label such as "Forge AI (deck name)" is longer than a player name. */
    public static String cleanName(String raw, int max) {
        if (raw == null) return DEFAULT_NAME;
        String s = raw.replace('#', ' ').replaceAll("\\p{Cntrl}", "").trim().replaceAll("\\s+", " ");
        if (s.length() > max) s = s.substring(0, max).trim();
        return s.isEmpty() ? DEFAULT_NAME : s;
    }
}

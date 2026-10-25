package forge.mtga.profile;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Mastery rules shared by the profile and the Front Door (docs/results-rank-mastery.md section 3): what every player owns from
 * the start, and the XP of a level. Everything else is earned on the mastery track (coordinator/src/mastery-track.json).
 */
public final class Mastery {
    private Mastery() { }

    /** The only avatar a new player owns. */
    public static final String DEFAULT_AVATAR = "Avatar_Basic_Adventurer";
    /** Always the LAST level of the track (after every other cosmetic): the hardest thing to earn (user, session 20). */
    public static final String CHAMPION_TITLE = "Title_Core_Champion";
    public static final int LEVEL_XP = 1_000;

    /** Never on the track: the Adventurer, the default card back, the five basic phrases. */
    public static final Set<String> ALWAYS_OWNED;
    static {
        Set<String> s = new LinkedHashSet<>(List.of(DEFAULT_AVATAR, Profile.DEFAULT_SLEEVE));
        s.addAll(Profile.DEFAULT_EMOTES);
        ALWAYS_OWNED = java.util.Collections.unmodifiableSet(s);
    }
}

package forge.mtga;

import java.util.Map;

/**
 * What the next Match Door connection plays: decks by Forge card name, the client GrpIds to show for the human's
 * chosen printings, and the match id the Front Door announced. The Front Door publishes one with {@link #publish}
 * when a match is created; the Match Door picks up the latest (rig/duel mode uses the hard-coded decks instead).
 */
public record MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                         Map<String, Integer> grpOverrides, String aiName) {
    private static volatile MatchSetup current;

    public static void publish(MatchSetup s) { current = s; }

    /** The match the Front Door created last, or null (direct-to-duel mode). */
    public static MatchSetup current() { return current; }
}

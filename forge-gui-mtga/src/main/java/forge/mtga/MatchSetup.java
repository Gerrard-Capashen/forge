package forge.mtga;

import java.util.Map;

/**
 * What the next Match Door connection plays ({@code seat} set: a player-vs-player seat prepared by the Front Door, either the
 * host's engine seat or the guest's relay to the host; else a match against the AI): decks by Forge card name, the client GrpIds to show for the human's
 * chosen printings, and the match id the Front Door announced. The Front Door publishes one with {@link #publish}
 * when a match is created ({@code gamesToWin} 2 = best of three, played with {@code humanSideboard} between games; {@code humanCompanion}/{@code aiCompanion}: each deck's companion card, played from the sideboard zone); the Match Door picks up the latest (rig/duel mode uses the hard-coded decks instead).
 */
public record MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                         Map<String, Integer> grpOverrides, String aiName, forge.mtga.net.GameBackend seat,
                         java.util.function.Consumer<Boolean> onResult, Map<String, Integer> humanSideboard, int gamesToWin,
                         String humanCompanion, String aiCompanion) {
    /** No companions. */
    public MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                      Map<String, Integer> grpOverrides, String aiName, forge.mtga.net.GameBackend seat,
                      java.util.function.Consumer<Boolean> onResult, Map<String, Integer> humanSideboard, int gamesToWin) {
        this(matchId, humanDeck, aiDeck, grpOverrides, aiName, seat, onResult, humanSideboard, gamesToWin, null, null);
    }

    /** One game, no sideboard. */
    public MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                      Map<String, Integer> grpOverrides, String aiName, forge.mtga.net.GameBackend seat,
                      java.util.function.Consumer<Boolean> onResult) {
        this(matchId, humanDeck, aiDeck, grpOverrides, aiName, seat, onResult, Map.of(), 1);
    }

    /** Without a result callback. */
    public MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                      Map<String, Integer> grpOverrides, String aiName, forge.mtga.net.GameBackend seat) {
        this(matchId, humanDeck, aiDeck, grpOverrides, aiName, seat, null);
    }

    /** A match against Forge's AI: the Match Door builds the backend from these decks. */
    public MatchSetup(String matchId, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck,
                      Map<String, Integer> grpOverrides, String aiName) {
        this(matchId, humanDeck, aiDeck, grpOverrides, aiName, null);
    }

    private static volatile MatchSetup current;

    public static void publish(MatchSetup s) { current = s; }

    /** The match the Front Door created last, or null (direct-to-duel mode). */
    public static MatchSetup current() { return current; }
}

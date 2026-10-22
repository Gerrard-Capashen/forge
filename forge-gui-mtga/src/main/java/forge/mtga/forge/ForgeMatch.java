package forge.mtga.forge;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One Forge game shared by the Match Door sessions of its human seats: a human against Forge's AI (one
 * {@link ForgeBackend}) or two humans (two backends, one per client). Each backend sees itself as client seat 1 and its
 * opponent as seat 2, so nothing in the GRE seat numbering depends on which Forge player a client is.
 * The game thread starts once every human seat has connected.
 */
public final class ForgeMatch {
    /** What a seat plays: display name, deck by Forge card name, the printings the player chose (card name -> GrpId). */
    public record Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides) { }

    private final CardDb db;
    private final String matchId;
    private final Seat[] seats = new Seat[2];
    private final ForgeBackend[] humans = new ForgeBackend[2];     // null = Forge AI
    private String aiName = "Forge AI";
    private int ready;
    private Thread thread;

    public ForgeMatch(CardDb db, String matchId) {
        this.db = db;
        this.matchId = matchId;
    }

    public String matchId() { return matchId; }

    synchronized void addHuman(int index, ForgeBackend backend, Seat seat) {
        humans[index] = backend;
        seats[index] = seat;
        maybeStart();
    }

    public synchronized void addAi(int index, Seat seat, String name) {
        humans[index] = null;
        seats[index] = seat;
        if (name != null) aiName = name;
        maybeStart();
    }

    private int humanSeats() { return (humans[0] != null ? 1 : 0) + (humans[1] != null ? 1 : 0); }

    /** A human seat's client sent its ConnectReq (reconnects are not supported yet). */
    synchronized void ready(ForgeBackend b) {
        ready++;
        maybeStart();
    }

    /** Both seats are defined and every human seat's client has connected. */
    private void maybeStart() {
        if (thread != null || seats[0] == null || seats[1] == null || ready < humanSeats()) return;
        thread = new Thread(this::run, "forge-game");
        thread.setDaemon(true);
        thread.start();
    }

    /** A client left: wake every other human seat's pending decision so the game ends instead of waiting for them. */
    void seatLeft(ForgeBackend leaving) {
        for (ForgeBackend b : humans) if (b != null && b != leaving) b.wake();
    }

    boolean opponentLeft(ForgeBackend me) {
        for (ForgeBackend b : humans) if (b != null && b != me && b.isDisconnected()) return true;
        return false;
    }

    /** Called by the first seat that reaches its mulligan: the other human seats get their ConnectResp + opening state now. */
    void introduceOthers(ForgeBackend first) {
        for (ForgeBackend b : humans) if (b != null && b != first) b.sendIntro(false);
    }

    private void run() {
        try {
            GameRules rules = new GameRules(GameType.Constructed);
            rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
            List<RegisteredPlayer> players = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                RegisteredPlayer rp = new RegisteredPlayer(ForgeSmoke.deck(i == 0 ? "You" : "Opponent", seats[i].deck()));
                rp.setPlayer(humans[i] != null ? humans[i].newLobbyPlayer() : GamePlayerUtil.createAiPlayer(aiName, 1, ""));
                players.add(rp);
            }

            Map<String, Integer> grpByName = new HashMap<>();
            for (Seat s : seats) for (String n : s.deck().keySet()) grpByName.put(n, db.grpId(n));

            Match match = new Match(rules, players, "MTGA");
            Game game = match.createGame();
            for (int i = 0; i < 2; i++) {
                if (humans[i] == null) continue;
                // the printings the viewer chose win over the opponent's for a card both play
                Map<String, Integer> view = new HashMap<>(grpByName);
                if (seats[1 - i].overrides() != null) view.putAll(seats[1 - i].overrides());
                if (seats[i].overrides() != null) view.putAll(seats[i].overrides());
                Player me = game.getPlayers().get(i);
                humans[i].bind(game, me, game.getPlayers().get(1 - i), view);
            }
            game.setNoGUIUser();
            match.startGame(game);
            System.out.println("[mtga] game over");
            for (ForgeBackend b : humans) if (b != null) b.sendGameOver();
        } catch (Throwable t) {
            if (t instanceof InterruptedException || t.getCause() instanceof InterruptedException) return;
            System.out.println("[mtga] game thread died: " + t);
            t.printStackTrace(System.out);
            for (ForgeBackend b : humans) if (b != null) b.matchAborted();
        }
    }
}

package forge.mtga.forge;

import forge.game.Game;
import forge.game.GameRules;
import forge.game.GameType;
import forge.game.Match;
import forge.game.player.Player;
import forge.game.player.RegisteredPlayer;
import forge.deck.CardPool;
import forge.player.GamePlayerUtil;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    public record Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard) {
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides) { this(name, deck, overrides, Map.of()); }
    }

    /** Games a seat must win to take the match: 1 = a single game, 2 = best of three (the human sideboards between games). */
    private int gamesToWin = 1;

    public void setGamesToWin(int n) { gamesToWin = Math.max(1, n); }

    int gamesToWin() { return gamesToWin; }

    private final CardDb db;
    private final String matchId;
    private final Seat[] seats = new Seat[2];
    private final ForgeBackend[] humans = new ForgeBackend[2];     // null = Forge AI
    private String aiName = "Forge AI";
    private int ready;
    private Thread thread;
    private volatile Game game;

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

    /** Unwinds the game thread out of a pending decision once a concession has ended the game ({@link #run} sends the result). */
    static final class Conceded extends RuntimeException {
        Conceded() { super("a player conceded", null, false, false); }
    }

    /**
     * A client conceded: the game ends now, not when that player is next asked something. The other human seats' pending
     * decisions wake up and apply it on the game thread ({@link #applyConcessions}); with the AI the next game event does.
     */
    void conceded(ForgeBackend b) {
        System.out.println("[mtga] seat " + indexOf(b) + " concedes");
        for (ForgeBackend o : humans) if (o != null && o != b) o.wakeConcede();
    }

    /** Game thread: every seat that asked to concede loses now. True if that ended something just now. */
    boolean applyConcessions() {
        Game g = game;
        if (g == null || g.isGameOver()) return false;
        boolean any = false;
        for (ForgeBackend b : humans) {
            if (b != null && b.concedeAsked() && b.player() != null && !b.player().conceded()) {
                b.player().concede();
                any = true;
            }
        }
        if (any) g.getAction().checkGameOverCondition();
        return any;
    }

    /** A client left: wake every other human seat's pending decision so the game ends instead of waiting for them. */
    void seatLeft(ForgeBackend leaving) {
        for (ForgeBackend b : humans) if (b != null && b != leaving) b.wake();
    }

    /**
     * A client's UI message: emotes (OnChat) and pet gestures (OnGenericEvent) go to the other human seat as sent. Hover and
     * select name objects by the sender's instance ids, which mean nothing to the other client, so they are dropped.
     */
    void relayUi(ForgeBackend from, com.wizards.mtga.gre.UIMessage ui) {
        if (!ui.hasOnChat() && !ui.hasOnGenericEvent()) return;
        if (ui.hasOnChat()) System.out.println("[mtga] emote '" + ui.getOnChat().getText() + "' from seat " + indexOf(from));
        for (ForgeBackend b : humans) if (b != null && b != from) b.deliverUi(ui);
    }

    private int indexOf(ForgeBackend b) { return humans[0] == b ? 0 : humans[1] == b ? 1 : -1; }

    boolean opponentLeft(ForgeBackend me) {
        for (ForgeBackend b : humans) if (b != null && b != me && b.isDisconnected()) return true;
        return false;
    }

    /**
     * Called by the first seat that reaches its mulligan: every human seat is asked keep or mulligan at once (the other seats get
     * their ConnectResp, opening state and question now), each client seeing the other as deciding.
     */
    void introduceOthers(ForgeBackend first) {
        for (ForgeBackend b : humans) if (b != null) setMulliganPending(b.player(), true, false);
        for (ForgeBackend b : humans) if (b != null && b != first) b.sendIntro(true);
    }

    /** A seat's mulligan question opened or closed: every human client learns it (with a diff when {@code push}). */
    void setMulliganPending(Player p, boolean on, boolean push) {
        for (ForgeBackend b : humans) if (b != null) b.setMulliganPending(p, on, push);
    }

    /** The hand or mulligan count of {@code changed} moved: the other human clients see the opponent's new hand size. */
    void refreshOthers(ForgeBackend changed) {
        for (ForgeBackend b : humans) if (b != null && b != changed) b.pushDiff();
    }

    /** A best-of-three that keeps drawing ends after this many games. */
    private static final int MAX_GAMES = 5;

    /** The main deck can't fall below this when sideboarding (a 40-card draft deck vs a 60-card constructed one). */
    private static int minDeck(Map<String, Integer> deck) { return Math.min(60, deck.values().stream().mapToInt(Integer::intValue).sum()); }

    private void run() {
        try {
            runMatch();
        } catch (Throwable t) {
            if (t instanceof InterruptedException || t.getCause() instanceof InterruptedException) return;
            System.out.println("[mtga] game thread died: " + t);
            t.printStackTrace(System.out);
            for (ForgeBackend b : humans) if (b != null) b.matchAborted();
        }
    }

    private void runMatch() throws Throwable {
        GameRules rules = new GameRules(GameType.Constructed);
        rules.setAppliedVariants(EnumSet.of(GameType.Constructed));
        rules.setGamesPerMatch(gamesToWin * 2 - 1);
        List<RegisteredPlayer> players = new ArrayList<>();
        Map<String, Integer>[] main = new Map[2];
        for (int i = 0; i < 2; i++) {
            main[i] = new LinkedHashMap<>(seats[i].deck());
            RegisteredPlayer rp = new RegisteredPlayer(ForgeSmoke.deck(i == 0 ? "You" : "Opponent", seats[i].deck()));
            rp.setPlayer(humans[i] != null ? humans[i].newLobbyPlayer() : GamePlayerUtil.createAiPlayer(aiName, 1, ""));
            players.add(rp);
        }
        Match match = new Match(rules, players, "MTGA");

        for (int gameNo = 1; ; gameNo++) {
            Map<String, Integer> grpByName = new HashMap<>();
            for (int i = 0; i < 2; i++) {
                for (String n : main[i].keySet()) grpByName.put(n, db.grpId(n));
                for (String n : seats[i].sideboard().keySet()) grpByName.put(n, db.grpId(n));
            }
            Game game = match.createGame();
            this.game = game;
            for (int i = 0; i < 2; i++) {
                if (humans[i] == null) continue;
                // the printings the viewer chose win over the opponent's for a card both play
                Map<String, Integer> view = new HashMap<>(grpByName);
                if (seats[1 - i].overrides() != null) view.putAll(seats[1 - i].overrides());
                if (seats[i].overrides() != null) view.putAll(seats[i].overrides());
                Player me = game.getPlayers().get(i);
                humans[i].bind(game, me, game.getPlayers().get(1 - i), view, StateSync.matchInfo(gameNo, gamesToWin, minDeck(seats[i].deck())));
            }
            game.setNoGUIUser();
            try {
                match.startGame(game);
                System.out.println("[mtga] game " + gameNo + " over");
            } catch (Throwable t) {
                if (t instanceof InterruptedException || t.getCause() instanceof InterruptedException) return;
                if (!game.isGameOver()) throw t;
                System.out.println("[mtga] game " + gameNo + " over (conceded)");     // a concession ended the game while someone was being asked
            }
            boolean conceded = false;
            for (Player p : game.getPlayers()) conceded |= p.conceded();
            boolean matchOver = gamesToWin <= 1 || conceded || match.isMatchOver() || gameNo >= MAX_GAMES;
            for (ForgeBackend b : humans) if (b != null) b.sendGameEnd(matchOver);
            if (matchOver) return;

            // every human seat sideboards at once (each client has its own timer); a seat that concedes or leaves ends the match
            Map<String, Integer>[] chosen = new Map[2];
            Thread[] threads = new Thread[2];
            for (int i = 0; i < 2; i++) {
                if (humans[i] == null) continue;
                // the sideboard is every card of the deck and sideboard that the main deck doesn't play now
                Map<String, Integer> side = new LinkedHashMap<>(seats[i].deck());
                seats[i].sideboard().forEach((n, c) -> side.merge(n, c, Integer::sum));
                main[i].forEach((n, c) -> side.merge(n, -c, Integer::sum));
                side.values().removeIf(n -> n <= 0);
                final int seat = i;
                final Map<String, Integer> curMain = main[i];
                threads[i] = new Thread(() -> {
                    chosen[seat] = humans[seat].sideboard(curMain, side);
                    if (chosen[seat] == null) seatLeft(humans[seat]);      // wake the other seat's screen
                }, "forge-sideboard-" + i);
                threads[i].setDaemon(true);
                threads[i].start();
            }
            for (Thread t : threads) if (t != null) t.join();
            boolean ended = false;
            for (int i = 0; i < 2; i++) ended |= humans[i] != null && chosen[i] == null;
            if (ended) {
                // a seat that is still here wins the match by forfeit
                for (int i = 0; i < 2; i++) if (humans[i] != null && chosen[i] != null && !humans[i].isDisconnected()) humans[i].matchWonByForfeit();
                return;
            }
            for (int i = 0; i < 2; i++) {
                if (humans[i] == null) continue;
                main[i] = chosen[i];
                CardPool pool = players.get(i).getDeck().getMain();
                pool.clear();
                pool.addAll(ForgeSmoke.deck("deck", chosen[i]).getMain());
            }
        }
    }
}

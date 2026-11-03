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
    public record Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard, String companion, List<String> commanders, List<String> schemes, int life) {
        /** {@code schemes}: a scheme deck (Forge names) makes the seat the Archenemy. */
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard, String companion, List<String> commanders) { this(name, deck, overrides, sideboard, companion, commanders, List.of(), 0); }
        /** {@code life}: the seat's starting life, 0 = what the rules give. */
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard, String companion, List<String> commanders, List<String> schemes) { this(name, deck, overrides, sideboard, companion, commanders, schemes, 0); }
        /** {@code commanders}: the deck's command zone cards (a Brawl deck's commander), Forge names. */
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard, String companion) { this(name, deck, overrides, sideboard, companion, List.of()); }
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides, Map<String, Integer> sideboard) { this(name, deck, overrides, sideboard, null); }
        public Seat(String name, Map<String, Integer> deck, Map<String, Integer> overrides) { this(name, deck, overrides, Map.of(), null); }
    }

    /** The commanders (Forge names) a seat plays, empty if it has none or is not seated yet. */
    public List<String> commanders(int seat) {
        Seat s = seats[seat];
        return s == null || s.commanders() == null ? List.of() : s.commanders();
    }

    /** Games a seat must win to take the match: 1 = a single game, 2 = best of three (the human sideboards between games). */
    private int gamesToWin = 1;

    public void setGamesToWin(int n) { gamesToWin = Math.max(1, n); }

    int gamesToWin() { return gamesToWin; }

    private boolean momir;

    /** Momir Basic: both seats play their basic lands with Momir Vig's avatar (the {X}, discard a card: random creature token ability) in the command zone. */
    public void setMomir(boolean on) { momir = on; }

    /** The Momir Basic avatar card (a Forge variant card), null if Forge lacks it. */
    public static final String MOMIR_AVATAR = forge.mtga.Archenemy.MOMIR_AVATAR;

    private int firstSeat = -1;

    /** The first game's starting player: seat 0 or 1 (a challenge's "play first" setting), -1 = the coin toss decides. */
    public void setFirstSeat(int seat) { firstSeat = seat; }

    /** Game thread: the player the settings make the first game's starter, null when the coin toss decides. */
    Player forcedStarter() { return firstSeat < 0 || game == null || firstSeat >= game.getPlayers().size() ? null : game.getPlayers().get(firstSeat); }

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
    private boolean mulliganOpened;

    void introduceOthers(ForgeBackend first) {
        if (mulliganOpened) return;
        mulliganOpened = true;
        for (ForgeBackend b : humans) if (b != null) setMulliganPending(b.player(), true, false);
        for (ForgeBackend b : humans) if (b != null && b != first) b.sendIntro(true);
    }

    /** A seat's mulligan question opened or closed: every human client learns it (with a diff when {@code push}). */
    void setMulliganPending(Player p, boolean on, boolean push) {
        for (ForgeBackend b : humans) if (b != null) b.setMulliganPending(p, on, push);
    }

    /**
     * Game thread: {@code chooser} (the loser of the last game) decides who plays first. The other human seat gets the
     * opening state at once, its client shows "waiting for opponent" from the chooser's pending ChooseStartingPlayerResp.
     */
    void startChoiceOpened(ForgeBackend chooser) {
        for (ForgeBackend b : humans) if (b != null) b.setStartPending(chooser.player());
        for (ForgeBackend b : humans) if (b != null && b != chooser) b.sendWaitingOpening(chooser.player());
    }

    /** The choice is made: every human client learns the pending question is gone. */
    void startChoiceClosed(ForgeBackend chooser) {
        for (ForgeBackend b : humans) if (b != null) b.clearStartPending(chooser.player());
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
        // a deck with a commander makes it a Brawl game (25 life, the command zone, commander tax)
        boolean brawl = false;
        for (Seat seat : seats) brawl |= seat.commanders() != null && !seat.commanders().isEmpty();
        // a seat with a scheme deck is the Archenemy (Archenemy: Sparky); the game is a Brawl game with that variant on top
        boolean archenemy = false;
        for (Seat seat : seats) archenemy |= seat.schemes() != null && !seat.schemes().isEmpty();
        if (momir) brawl = archenemy = false;      // Momir Basic is its own game: 24 life from the avatar, no command zone cards
        GameType type = brawl ? GameType.Brawl : GameType.Constructed;
        EnumSet<GameType> variants = EnumSet.of(type);
        if (archenemy) variants.add(GameType.Archenemy);
        if (momir) variants.add(GameType.MomirBasic);
        GameRules rules = new GameRules(type);
        rules.setAppliedVariants(variants);
        // the avatar copies a creature the client can draw: only cards it has a row for (Arena's own cards and the generated ones)
        if (momir) rules.setRandomCopyPool(pc -> db.grpId(pc.getName()) != 0);
        rules.setGamesPerMatch(gamesToWin * 2 - 1);
        List<RegisteredPlayer> players = new ArrayList<>();
        Map<String, Integer>[] main = new Map[2];
        for (int i = 0; i < 2; i++) {
            main[i] = new LinkedHashMap<>(seats[i].deck());
            forge.deck.Deck deck = ForgeSmoke.deck(i == 0 ? "You" : "Opponent", seats[i].deck());
            // a companion waits in the sideboard; Forge's Match moves it to the command zone before the first game (Player.assignCompanion)
            String companion = seats[i].companion();
            if (companion != null && !companion.isBlank()) {
                forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName(companion);
                if (pc != null) deck.getOrCreate(forge.deck.DeckSection.Sideboard).add(pc, 1);
            }
            if (brawl) {
                for (String commander : seats[i].commanders()) {
                    forge.item.PaperCard pc = forge.StaticData.instance().getCommonCards().getUniqueByName(commander);
                    if (pc != null) deck.getOrCreate(forge.deck.DeckSection.Commander).add(pc, 1);
                }
            }
            boolean boss = seats[i].schemes() != null && !seats[i].schemes().isEmpty();
            List<forge.item.PaperCard> schemes = new ArrayList<>();
            if (boss) {
                for (String scheme : seats[i].schemes()) {
                    forge.item.PaperCard pc = forge.StaticData.instance().getVariantCards().getUniqueByName(scheme);
                    if (pc == null) System.out.println("[mtga] Archenemy: Forge has no scheme " + scheme);
                    else schemes.add(pc);
                }
            }
            if (momir) {
                forge.item.PaperCard avatar = forge.StaticData.instance().getVariantCards().getUniqueByName(MOMIR_AVATAR);
                if (avatar == null) throw new IllegalStateException("Forge has no card " + MOMIR_AVATAR);
                deck.getOrCreate(forge.deck.DeckSection.Avatar).add(avatar, 1);
            }
            RegisteredPlayer rp = brawl || archenemy || momir ? RegisteredPlayer.forVariants(2, variants, deck, schemes, boss, null, null) : new RegisteredPlayer(deck);
            if (seats[i].life() > 0) rp.setStartingLife(seats[i].life());
            rp.setPlayer(humans[i] != null ? humans[i].newLobbyPlayer() : GamePlayerUtil.createAiPlayer(aiName, 1, ""));
            players.add(rp);
        }
        Match match = new Match(rules, players, "MTGA");

        for (int gameNo = 1; ; gameNo++) {
            Map<String, Integer> grpByName = new HashMap<>();
            for (int i = 0; i < 2; i++) {
                for (String n : main[i].keySet()) grpByName.put(n, db.grpId(n));
                for (String n : seats[i].sideboard().keySet()) grpByName.put(n, db.grpId(n));
                if (seats[i].companion() != null) grpByName.put(seats[i].companion(), db.grpId(seats[i].companion()));
                for (String commander : seats[i].commanders()) grpByName.put(commander, db.grpId(commander));
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
                humans[i].bind(game, me, game.getPlayers().get(1 - i), view, StateSync.matchInfo(gameNo, gamesToWin, minDeck(seats[i].deck()), brawl));
            }
            game.setNoGUIUser();
            mulliganOpened = false;
            try {
                match.startGame(game);
                System.out.println("[mtga] game " + gameNo + " over");
            } catch (Throwable t) {
                if (t instanceof InterruptedException || t.getCause() instanceof InterruptedException) return;
                if (!game.isGameOver()) throw t;
                System.out.println("[mtga] game " + gameNo + " over (conceded)");     // a concession ended the game while someone was being asked
            }
            // a best of three goes on after a game concession; a match concession (or a single game) ends it
            boolean matchConceded = false;
            for (ForgeBackend b : humans) matchConceded |= b != null && b.concedeMatchAsked();
            if (!matchConceded) for (ForgeBackend b : humans) if (b != null) b.clearConcessions();
            boolean matchOver = gamesToWin <= 1 || matchConceded || match.isMatchOver() || gameNo >= MAX_GAMES;
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

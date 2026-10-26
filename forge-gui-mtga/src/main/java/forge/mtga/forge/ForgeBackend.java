package forge.mtga.forge;

import com.google.common.eventbus.Subscribe;
import com.wizards.mtga.gre.Action;
import com.wizards.mtga.gre.ActionType;
import com.wizards.mtga.gre.ClientMessageType;
import com.wizards.mtga.gre.ClientToGREMessage;
import com.wizards.mtga.gre.ConnectionStatus;
import com.wizards.mtga.gre.GREMessageType;
import com.wizards.mtga.gre.GREToClientMessage;
import com.wizards.mtga.gre.GameStateMessage;
import com.wizards.mtga.gre.MatchGameRoomStateType;
import com.wizards.mtga.gre.MulliganOption;
import com.wizards.mtga.gre.ParameterType;
import com.wizards.mtga.gre.PromptParameter;
import forge.ai.LobbyPlayerAi;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.card.CardCollectionView;
import forge.game.event.GameEvent;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.AbilitySub;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;
import forge.LobbyPlayer;
import forge.mtga.net.GameBackend;
import forge.mtga.net.MatchSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Predicate;

/**
 * One human seat of a Forge game driven through the MTGA Match Door: this client is seat 1, its opponent (Forge AI or
 * the other human's backend of the same {@link ForgeMatch}) is seat 2.
 * The Forge game runs on its own thread; the human's decisions block it until the client answers.
 */
public final class ForgeBackend implements GameBackend {
    private static final String DEFAULT_MATCH_ID = "local-match-1";

    // prompt ids from the client card DB (table Prompts -> Localizations_enUS)
    private static final int PROMPT_MULLIGAN = 34, PROMPT_TARGETS = 1041, PROMPT_DISCARD = 14, PROMPT_CHOOSE_MODES = 83,
            PROMPT_X = 51, PROMPT_ORDER = 42, PROMPT_PAY_2_LIFE = 2233, PROMPT_PAY_N_LIFE = 8167, PROMPT_CHOOSE_CARD = 2428,
            PROMPT_CHOOSE_TWO = 193, PROMPT_SACRIFICE = 1029, PROMPT_EXPLORE = 115,
            PROMPT_RETURN_CREATURE = 1422;

    private final CardDb db;
    private final AbilityMap abilities;
    private final ForgeMatch room;
    private final int index;
    private final ForgeMatch.Seat seat;
    private final String matchId;

    private volatile MatchSession session;
    private final ClientSettings settings = new ClientSettings();
    private com.wizards.mtga.gre.ProtoVersion protoVer = com.wizards.mtga.gre.ProtoVersion.forNumber(0);
    private final LinkedBlockingQueue<ClientToGREMessage> inbox = new LinkedBlockingQueue<>();
    private Game game;
    private Player human, opponent;
    /** The client left (set on a Netty thread). */
    private volatile boolean disconnected;
    private StateSync sync;
    /** Card name -> the GrpId this client shows it with (its chosen printings win over the opponent's). */
    private Map<String, Integer> view = Map.of();
    /** The winning team of each game of the match so far (0 = draw). */
    private final List<Integer> gameWinners = new ArrayList<>();
    private boolean introSent, playStageSent;
    /** While Forge redraws a mulligan hand, send one diff at the next prompt instead of one per card move. */
    private boolean batching;
    private int mulligans;
    /** offered action key -> Forge ability, for the last ActionsAvailableReq. */
    private final Map<String, SpellAbility> offered = new HashMap<>();
    /** The mana abilities of each offer's auto-tap preview; the chosen one is tapped for real before Forge pays the rest. */
    private final Map<String, List<SpellAbility>> offeredTaps = new HashMap<>();
    private List<SpellAbility> pendingTaps;
    /** Color each previewed source pays with (dual lands etc. must make that color when pre-tapped). */
    private final Map<String, Map<SpellAbility, String>> offeredTapColors = new HashMap<>();
    private Map<SpellAbility, String> pendingTapColors;
    /** Cards castable from exile/graveyard (instance id -> actions), shown next to the hand via GameStateMessage.actions. */
    private final Map<Integer, List<Action>> otherZoneActions = new LinkedHashMap<>();
    private static final ClientToGREMessage POISON = ClientToGREMessage.newBuilder().setType(ClientMessageType.ClientMessageType_None).build();
    private static final ClientToGREMessage CONCEDE_WAKE = ClientToGREMessage.newBuilder().setType(ClientMessageType.ClientMessageType_None).build();
    /** This seat's client sent ConcedeReq (read by the game thread, see ForgeMatch.applyConcessions). */
    private volatile boolean concedeAsked;

    public ForgeBackend(CardDb db, Map<String, Integer> humanDeck, Map<String, Integer> aiDeck) {
        this(db, new forge.mtga.MatchSetup(DEFAULT_MATCH_ID, humanDeck, aiDeck, Map.of(), "Forge AI"));
    }

    /** A human (seat 1) against Forge's AI. */
    public ForgeBackend(CardDb db, forge.mtga.MatchSetup setup) {
        this(db, new ForgeMatch(db, setup.matchId() == null ? DEFAULT_MATCH_ID : setup.matchId()), 0,
                new ForgeMatch.Seat(System.getProperty("mtga.name", "Forge Player"), setup.humanDeck(), setup.grpOverrides(), setup.humanSideboard()));
        room.setGamesToWin(setup.gamesToWin());
        room.addAi(1, new ForgeMatch.Seat("Opponent", setup.aiDeck(), Map.of()), setup.aiName() == null ? "Forge AI" : setup.aiName());
        this.onResult = setup.onResult();
    }

    /** Told once whether the human won the game (event matches count wins and losses). */
    private java.util.function.Consumer<Boolean> onResult;

    /** Told once how the match ended for this seat: "win", "loss" or "draw" (online matches report it to the coordinator). */
    private volatile java.util.function.Consumer<String> outcomeListener;

    public void setOutcomeListener(java.util.function.Consumer<String> l) { outcomeListener = l; }

    /** This seat's quest counters (QuestMetrics), handed over once when the match ends; by default to the registry the Front Door reads. */
    private final QuestMetrics metrics = new QuestMetrics();
    private volatile java.util.function.Consumer<com.google.gson.JsonObject> metricsListener;

    /** Where this seat's quest counters go when the match ends (the host sends its guest's seat over the peer link). */
    public void setMetricsListener(java.util.function.Consumer<com.google.gson.JsonObject> l) { metricsListener = l; }

    private void tellOutcome(String outcome) {
        java.util.function.Consumer<com.google.gson.JsonObject> ml = metricsListener;
        metricsListener = null;
        if (ml != null) {
            com.google.gson.JsonObject m = metrics.toJson();
            m.addProperty("MatchPlayed", 1);
            if ("win".equals(outcome)) m.addProperty("MatchWon", 1);
            ml.accept(m);
        }
        java.util.function.Consumer<String> l = outcomeListener;
        outcomeListener = null;
        if (l != null) l.accept(outcome);
    }

    /** One human seat of {@code room}; add the other seat (a second backend or {@link ForgeMatch#addAi}) before the clients connect. */
    public ForgeBackend(CardDb db, ForgeMatch room, int index, ForgeMatch.Seat seat) {
        this.db = db;
        this.abilities = new AbilityMap(db);
        this.room = room;
        this.index = index;
        this.seat = seat;
        this.matchId = room.matchId();
        this.metricsListener = m -> QuestMetrics.publish(matchId, m);
        room.addHuman(index, this, seat);
    }

    // ---- transport callbacks (Netty threads) --------------------------------------------------------------------

    @Override
    public synchronized void onConnect(MatchSession s, ClientToGREMessage connectReq) {
        if (session != null) return;       // reconnects are not supported yet
        session = s;
        protoVer = connectReq.getConnectReq().getProtoVer();
        settings.merge(connectReq.getConnectReq().getDefaultSettings());
        System.out.println("[mtga] default settings: " + settings);
        room.ready(this);
    }

    @Override
    public void onClientMessage(MatchSession s, ClientToGREMessage msg) {
        forge.mtga.net.Capture.log("CLIENT -> " + forge.mtga.net.Capture.flat(msg, 1200));
        switch (msg.getType()) {
            case ClientMessageType_ConcedeReq -> {
                concedeAsked = true;            // at once, whoever the game waits for (see ForgeMatch.conceded)
                inbox.add(msg);
                room.conceded(this);
            }
            case ClientMessageType_MulliganResp, ClientMessageType_PerformActionResp,
                 ClientMessageType_DeclareAttackersResp, ClientMessageType_SubmitAttackersReq,
                 ClientMessageType_DeclareBlockersResp, ClientMessageType_SubmitBlockersReq,
                 ClientMessageType_SelectTargetsResp, ClientMessageType_SubmitTargetsReq, ClientMessageType_CancelActionReq,
                 ClientMessageType_GroupResp, ClientMessageType_SelectNResp, ClientMessageType_OptionalActionResp,
                 ClientMessageType_NumericInputResp, ClientMessageType_OrderResp, ClientMessageType_CastingTimeOptionsResp,
                 ClientMessageType_EffectCostResp, ClientMessageType_EnterSideboardingReq, ClientMessageType_SubmitDeckResp -> inbox.add(msg);
            case ClientMessageType_SetSettingsReq -> {
                settings.merge(msg.getSetSettingsReq().getSettings(), msg.getSetSettingsReq().getTurnNumber());
                System.out.println("[mtga] settings now: " + settings);
                GREToClientMessage.Builder resp = GREToClientMessage.newBuilder().setType(GREMessageType.GREMessageType_SetSettingsResp)
                        .addSystemSeatIds(StateSync.HUMAN_SEAT).setGameStateId(sync == null ? 0 : sync.lastStateId());
                resp.getSetSettingsRespBuilder().setSettings(settings.toMessage());
                s.sendGre(resp);
            }
            case ClientMessageType_UIMessage -> room.relayUi(this, msg.getUiMessage());
            default -> forge.mtga.net.Capture.log("UNHANDLED client message " + msg.getType());
        }
    }

    @Override
    public void onDisconnect(MatchSession s) {
        disconnected = true;
        inbox.add(POISON);
        room.seatLeft(this);
    }

    /** The other seat's client left: a pending decision here must end too. */
    void wake() { inbox.add(POISON); }

    /** The other seat conceded: a pending decision here ends the game now (the concession is applied on the game thread). */
    void wakeConcede() { inbox.add(CONCEDE_WAKE); }

    boolean concedeAsked() { return concedeAsked; }

    Player player() { return human; }

    /** An emote (or pet gesture) the opponent's client sent, passed on to this seat's client; it draws it on the opponent's side. */
    void deliverUi(com.wizards.mtga.gre.UIMessage ui) {
        MatchSession s = session;
        if (s == null) return;
        GREToClientMessage.Builder m = GREToClientMessage.newBuilder().setType(GREMessageType.GREMessageType_UIMessage)
                .addSystemSeatIds(StateSync.HUMAN_SEAT).setGameStateId(sync == null ? 0 : sync.lastStateId());
        m.setUiMessage(ui.toBuilder().clearSeatIds().addSeatIds(StateSync.AI_SEAT));
        s.sendGre(m);
    }

    LobbyPlayer newLobbyPlayer() { return new MtgaLobbyPlayer(seat.name()); }

    // ---- game thread -------------------------------------------------------------------------------------------

    /** The shared game exists (game thread, before it starts): this seat plays {@code me} against {@code opp}. */
    void bind(Game g, Player me, Player opp, Map<String, Integer> grpByName, StateSync.GameInfoSupplier info) {
        game = g;
        human = me;
        opponent = opp;
        view = grpByName;
        // a later game of a best-of-three starts from a clean slate, but the client keeps counting message ids
        introSent = playStageSent = batching = false;
        mulligans = 0;
        offered.clear();
        offeredTaps.clear();
        pendingTaps = null;
        offeredTapColors.clear();
        pendingTapColors = null;
        otherZoneActions.clear();
        StateSync previous = sync;
        settings.setTurnSupplier(() -> Math.max(0, game.getPhaseHandler().getTurn()));      // the client's TurnInfo.turnNumber
        sync = new StateSync(game, human, opponent, db, grpByName, matchId);
        sync.setInfo(info);
        if (previous != null) sync.continueFrom(previous);
        sync.setAbilityResolver(sa -> {
            Card host = sa == null ? null : StateSync.visibleSource(sa.getHostCard());
            return host == null ? 0 : abilities.abilityId(sa, grp(host));
        });
        metrics.bind(me.getView());
        game.subscribeToEvents(new Object() {
            @Subscribe public void on(GameEvent ev) {
                metrics.on(ev);
                onGameEvent(ev);
            }
        });
    }

    private int grp(Card c) { return sync != null ? sync.grpIdOf(c) : db.grpId(c.getName()); }

    /** Test/debug aid: Forge card name behind an instance id. */
    public String debugName(int iid) {
        Card c = sync == null ? null : sync.card(iid);
        forge.game.spellability.SpellAbilityStackInstance si = c == null && sync != null ? sync.stackItem(iid) : null;
        if (si != null) return "ability of " + si.getSourceCard() + ": " + si.getSpellAbility().getDescription() + " trigger=" + si.isTrigger();
        return c == null ? "?" + iid : c.getName() + (c.isToken() ? " (token)" : "") + " in " + c.getZone();
    }

    private int iid(Card c) { return sync.iid(c); }

    /** The game on the Forge side is over: report it; {@code matchOver} also ends the match, else the sideboarding that follows is {@link #sideboard}. */
    void sendGameEnd(boolean matchOver) {
        boolean draw = game.getOutcome().isDraw();
        boolean humanWon = !draw && game.getOutcome().getWinningLobbyPlayer() == human.getLobbyPlayer();
        gameWinners.add(draw ? 0 : humanWon ? StateSync.HUMAN_SEAT : StateSync.AI_SEAT);
        sendResults(matchOver, true, matchWinner());
        if (!matchOver) return;
        int winner = matchWinner();
        java.util.function.Consumer<Boolean> cb = onResult;
        onResult = null;
        if (cb != null && winner != 0) cb.accept(winner == StateSync.HUMAN_SEAT);
        tellOutcome(winner == 0 ? "draw" : winner == StateSync.HUMAN_SEAT ? "win" : "loss");
    }

    /** Team that won the match: the one with enough game wins, else (a concession, a run of draws) the last game's winner; 0 = draw. */
    private int matchWinner() {
        int[] wins = new int[3];
        for (int w : gameWinners) wins[w]++;
        int need = room.gamesToWin();
        if (wins[StateSync.HUMAN_SEAT] >= need) return StateSync.HUMAN_SEAT;
        if (wins[StateSync.AI_SEAT] >= need) return StateSync.AI_SEAT;
        return gameWinners.isEmpty() ? 0 : gameWinners.get(gameWinners.size() - 1);
    }

    /** The client conceded while sideboarding: the match is lost now. */
    private void concedeBetweenGames() {
        sendResults(true, false, StateSync.AI_SEAT);
        java.util.function.Consumer<Boolean> cb = onResult;
        onResult = null;
        if (cb != null) cb.accept(false);
        tellOutcome("loss");
    }

    /** The game thread died: when the opponent's client left, this seat wins by forfeit; otherwise nothing more can be sent. */
    void matchAborted() {
        if (disconnected || session == null || sync == null || !introSent) return;
        boolean opponentLeft = room.opponentLeft(this);
        if (opponentLeft) {
            gameWinners.add(StateSync.HUMAN_SEAT);
            sendResults(true, false, StateSync.HUMAN_SEAT);
            tellOutcome("win");
        }
    }

    /**
     * Reports the games played so far (and the match with {@code winningTeam} when {@code matchOver}). Between games it ends
     * with an IntermissionReq that offers the sideboard screen; the match's end also closes the room.
     */
    private void sendResults(boolean matchOver, boolean withStateDiff, int winningTeam) {
        List<com.wizards.mtga.gre.ResultSpec> results = new ArrayList<>();
        for (int w : gameWinners) results.add(resultSpec(com.wizards.mtga.gre.MatchScope.MatchScope_Game, w));
        List<com.wizards.mtga.gre.ResultSpec> games = new ArrayList<>(results);
        if (matchOver) results.add(resultSpec(com.wizards.mtga.gre.MatchScope.MatchScope_Match, winningTeam));
        sync.setResults(results, matchOver ? com.wizards.mtga.gre.MatchState.MatchState_MatchComplete : com.wizards.mtga.gre.MatchState.MatchState_GameComplete);
        GameStateMessage.Builder d = withStateDiff ? sync.diff(false) : sync.diff(false, true);
        GREToClientMessage.Builder inter = req(GREMessageType.GREMessageType_IntermissionReq);
        inter.getIntermissionReqBuilder().setResult(matchOver ? results.get(0) : games.get(games.size() - 1));
        if (!matchOver) {
            inter.getIntermissionReqBuilder().addOptions(com.wizards.mtga.gre.UserOption.newBuilder()
                    .setResponseType(ClientMessageType.ClientMessageType_EnterSideboardingReq));
        }
        if (d != null) session.sendGre(gameState(d), inter);
        else session.sendGre(inter);
        if (!matchOver) return;

        com.wizards.mtga.gre.MatchServiceToClientMessage.Builder out = com.wizards.mtga.gre.MatchServiceToClientMessage.newBuilder();
        com.wizards.mtga.gre.MatchGameRoomInfo.Builder info = out.getMatchGameRoomStateChangedEventBuilder().getGameRoomInfoBuilder();
        info.setStateType(MatchGameRoomStateType.MatchGameRoomStateType_MatchCompleted);
        info.getGameRoomConfigBuilder().setMatchId(matchId);
        info.getFinalMatchResultBuilder().setMatchId(matchId)
                .setMatchCompletedReason(com.wizards.mtga.gre.MatchCompletedReasonType.MatchCompletedReasonType_Success).addAllResultList(results);
        session.send(out, 0);
    }

    private static com.wizards.mtga.gre.ResultSpec resultSpec(com.wizards.mtga.gre.MatchScope scope, int winningTeam) {
        return com.wizards.mtga.gre.ResultSpec.newBuilder().setScope(scope)
                .setResult(winningTeam == 0 ? com.wizards.mtga.gre.ResultType.ResultType_Draw : com.wizards.mtga.gre.ResultType.ResultType_WinLoss)
                .setWinningTeamId(winningTeam).setReason(com.wizards.mtga.gre.ResultReason.ResultReason_Game).build();
    }

    /**
     * Between the games of a best-of-three (game thread): waits for the client to enter the sideboard screen, shows it
     * {@code main} and {@code side} (card name -> count) and returns the main deck it submits, or null if the client left
     * or conceded (the match is then over and reported).
     */
    Map<String, Integer> sideboard(Map<String, Integer> main, Map<String, Integer> side) {
        List<com.wizards.mtga.gre.ResultSpec> gameResults = new ArrayList<>();
        for (int w : gameWinners) gameResults.add(resultSpec(com.wizards.mtga.gre.MatchScope.MatchScope_Game, w));
        try {
            ClientToGREMessage in = await(m -> is(m, ClientMessageType.ClientMessageType_EnterSideboardingReq, ClientMessageType.ClientMessageType_ConcedeReq));
            if (in.getType() == ClientMessageType.ClientMessageType_ConcedeReq) { concedeBetweenGames(); return null; }
            GREToClientMessage.Builder reqMsg = GREToClientMessage.newBuilder().setType(GREMessageType.GREMessageType_SubmitDeckReq)
                    .addSystemSeatIds(StateSync.HUMAN_SEAT);
            com.wizards.mtga.gre.DeckMessage.Builder deck = reqMsg.getSubmitDeckReqBuilder().getDeckBuilder();
            addGrps(deck::addDeckCards, main);
            addGrps(deck::addSideboardCards, side);
            GameStateMessage.Builder state = sync.matchStateDiff(com.wizards.mtga.gre.MatchState.MatchState_Sideboarding, gameResults);
            reqMsg.setGameStateId(sync.lastStateId());
            session.sendGre(gameState(state), reqMsg);

            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_SubmitDeckResp, ClientMessageType.ClientMessageType_ConcedeReq));
            if (r.getType() == ClientMessageType.ClientMessageType_ConcedeReq) { concedeBetweenGames(); return null; }
            Map<String, Integer> chosen = new LinkedHashMap<>();
            for (int grp : r.getSubmitDeckResp().getDeck().getDeckCardsList()) {
                String name = db.name(grp);
                if (name != null) chosen.merge(name, 1, Integer::sum);
            }
            if (!legalSideboarding(chosen, main, side)) {
                System.out.println("[mtga] sideboard: submitted deck uses cards outside deck + sideboard, keeping the old main deck");
                chosen = main;
            }
            session.sendGre(req(GREMessageType.GREMessageType_SubmitDeckConfirmation));
            return chosen;
        } catch (IllegalStateException e) {
            if (disconnected) return null;     // the client left: nothing to send
            throw e;
        }
    }

    private void addGrps(java.util.function.IntConsumer into, Map<String, Integer> cards) {
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            int grp = view.getOrDefault(e.getKey(), db.grpId(e.getKey()));
            for (int i = 0; i < e.getValue(); i++) into.accept(grp);
        }
    }

    /** The submitted main deck draws only on the cards of main deck + sideboard (no more copies than the player has). */
    private static boolean legalSideboarding(Map<String, Integer> chosen, Map<String, Integer> main, Map<String, Integer> side) {
        for (Map.Entry<String, Integer> e : chosen.entrySet()) {
            if (e.getValue() > main.getOrDefault(e.getKey(), 0) + side.getOrDefault(e.getKey(), 0)) return false;
        }
        return !chosen.isEmpty();
    }

    private void onGameEvent(GameEvent ev) {
        room.applyConcessions();       // game thread: a concession while nobody is asked (the AI's turn) ends the game here
        if (!introSent) return;
        sync.onEvent(ev);
        if (ev instanceof forge.game.event.GameEventTurnBegan || ev instanceof forge.game.event.GameEventTurnPhase) batching = false;
        if (batching) return;       // collected into the next decision-time diff
        // All combat damage of a step must reach the client in ONE diff: the client builds one combat frame (attack
        // lunge + hits) per run of consecutive DamageDealt events, so one diff per creature showed two separate hits.
        // The held annotations go out with the next diff (deaths, next step or the next request).
        if ((ev instanceof forge.game.event.GameEventCardDamaged || ev instanceof forge.game.event.GameEventPlayerDamaged)
                && sync.dealingCombatDamage()) return;
        GameStateMessage.Builder d = sync.diff(false, false);
        if (d == null) return;
        session.sendGre(gameState(d));
        if (STEP_DELAY_MS > 0 && index == 0 && hasStepChange(d)) {
            // Forge runs steps without pause when nobody stops; give the client's phase ladder time to show each step.
            try { Thread.sleep(STEP_DELAY_MS); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }

    /** Pause after each step change the client did not stop at (-Dmtga.stepdelay=ms, ForgeMtgaMain default 150). */
    static final int STEP_DELAY_MS = Integer.getInteger("mtga.stepdelay", 0);

    private static boolean hasStepChange(GameStateMessage.Builder d) {
        for (com.wizards.mtga.gre.AnnotationInfo a : d.getAnnotationsList()) {
            if (a.getTypeList().contains(com.wizards.mtga.gre.AnnotationType.AnnotationType_PhaseOrStepModified)) return true;
        }
        return false;
    }

    /** Send a Diff for whatever changed since the last message (game thread only). */
    private void sendDiff(boolean forcePlayStage) { sendDiff(forcePlayStage, true); }

    private void sendDiff(boolean forcePlayStage, boolean force) {
        boolean play = forcePlayStage && !playStageSent;
        GameStateMessage.Builder d = sync.diff(play, force || play);
        if (d == null) return;
        if (play) playStageSent = true;
        session.sendGre(gameState(d));
    }

    /** Diff (if any) + the request in one batch, with the human shown as deciding player. */
    private void sendRequest(GREToClientMessage.Builder request) {
        batching = false;
        sync.setDecisionPlayer(human);
        GameStateMessage.Builder d = sync.diff(!playStageSent && introSent);
        if (d != null && !playStageSent) playStageSent = true;
        request.setGameStateId(sync.lastStateId());
        if (d != null) session.sendGre(gameState(d), request);
        else session.sendGre(request);
    }

    /** Called once the human answered: decisions go back to whoever Forge says has priority. */
    private void decided() {
        sync.setDecisionPlayer(null);
    }

    private GREToClientMessage.Builder gameState(GameStateMessage.Builder gs) {
        return GREToClientMessage.newBuilder().setType(GREMessageType.GREMessageType_GameStateMessage)
                .addSystemSeatIds(StateSync.HUMAN_SEAT).setGameStateId(gs.getGameStateId()).setGameStateMessage(gs);
    }

    private GREToClientMessage.Builder req(GREMessageType type) {
        return GREToClientMessage.newBuilder().setType(type).addSystemSeatIds(StateSync.HUMAN_SEAT).setGameStateId(sync.lastStateId());
    }

    private ClientToGREMessage await(Predicate<ClientToGREMessage> want) {
        try {
            while (true) {
                ClientToGREMessage m = inbox.take();
                if (m == POISON) throw new IllegalStateException("client disconnected");
                if (m == CONCEDE_WAKE) {
                    if (!room.applyConcessions()) continue;     // already applied (a stale wake-up)
                    throw new ForgeMatch.Conceded();
                }
                if (want.test(m)) {
                    decided();
                    return m;
                }
                if (m.getType() == ClientMessageType.ClientMessageType_ConcedeReq) {   // conceded while asked something else
                    room.applyConcessions();
                    throw new ForgeMatch.Conceded();
                }
                System.out.println("[mtga] ignoring out-of-turn client message " + m.getType());
            }
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean is(ClientToGREMessage m, ClientMessageType... types) {
        for (ClientMessageType t : types) if (m.getType() == t) return true;
        return false;
    }

    /** Client prompt id for a Forge question: exact text match in the client DB, else a few known phrasings. */
    private int promptFor(String forgeText) {
        int id = db.promptId(forgeText);
        if (id != 0 || forgeText == null) return id;
        String t = forgeText.toLowerCase();
        if (t.contains("shuffle")) return 7221;          // "Shuffle?"
        if (t.contains("draw a card")) return 1001;      // "Draw a card?"
        return 0;
    }

    /** Prompt for "move N cards from origin to destination" choices (Brainstorm put-back, searches...). */
    private int zoneChangePrompt(String forgeText, ZoneType destination, List<ZoneType> origin, int n) {
        int id = promptFor(forgeText);
        if (id != 0) return id;
        return n == 2 ? PROMPT_CHOOSE_TWO : PROMPT_CHOOSE_CARD;
    }

    private static PromptParameter numberParam(String name, int v) {
        return PromptParameter.newBuilder().setParameterName(name).setType(ParameterType.ParameterType_Number).setNumberValue(v).build();
    }

    // ---- mulligan ----------------------------------------------------------------------------------------------

    /**
     * London mulligan as MTGA shows it: keep/mulligan is always asked on a full 7, and only when keeping after N
     * mulligans does the client pick the N cards for the bottom (Forge would tuck right after each redraw).
     */
    boolean askMulligan(int toBottom) {
        sync.setMulliganCount(human, mulligans);
        if (!introSent) {
            if (System.getProperty("mtga.rig") != null) rigHand(human);
            room.introduceOthers(this);      // a second human gets its ConnectResp + opening state while this seat decides
            sendIntro(true);
        } else {
            batching = false;
            sync.setDecisionPlayer(human);
            sendDiff(false);
            session.sendGre(mulliganReq());
        }
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_MulliganResp, ClientMessageType.ClientMessageType_ConcedeReq));
        if (r.getType() == ClientMessageType.ClientMessageType_ConcedeReq) {
            human.concede();
            return true;
        }
        boolean keep = r.getMulliganResp().getDecision() == MulliganOption.MulliganOption_AcceptHand;
        System.out.println("[mtga] mulligan decision keep=" + keep + " (mulligans so far " + mulligans + ")");
        if (!keep) {
            batching = true;
            mulligans++;
            sync.setMulliganCount(human, mulligans);
            return false;
        }
        if (toBottom > 0) {
            CardCollectionView hand = human.getCardsIn(ZoneType.Hand);
            CardCollectionView bottom = askTuck(hand, toBottom);
            if (bottom.size() != toBottom) bottom = ((PlayerControllerAi) human.getController()).getAi().getCardsToDiscard(toBottom, null, null);
            for (Card c : bottom) game.getAction().moveToLibrary(c, -1, null);
            sendDiff(false);
        }
        return true;
    }

    /** ConnectResp + the opening state (+ the mulligan question when this seat decides first). Game thread only. */
    void sendIntro(boolean withMulliganReq) {
        if (introSent) return;
        boolean firstGame = gameWinners.isEmpty();
        GREToClientMessage.Builder connect = GREToClientMessage.newBuilder().setType(GREMessageType.GREMessageType_ConnectResp)
                .addSystemSeatIds(StateSync.HUMAN_SEAT);
        connect.getConnectRespBuilder().setStatus(ConnectionStatus.ConnectionStatus_Success).setProtoVer(protoVer).setSettings(settings.toMessage());
        for (Card c : human.getCardsIn(List.of(ZoneType.Hand, ZoneType.Library))) {
            connect.getConnectRespBuilder().getDeckMessageBuilder().addDeckCards(db.grpId(c.getName()));
        }
        sync.setDecisionPlayer(withMulliganReq ? human : null);
        // a later game of the match: no new connection, just the opening state of the new game
        GREToClientMessage.Builder opening = gameState(sync.full(sync.info()));
        if (firstGame) {
            if (withMulliganReq) session.sendGre(connect, opening, mulliganReq());
            else session.sendGre(connect, opening);
            session.sendRoomState(MatchGameRoomStateType.MatchGameRoomStateType_Playing, matchId);
        } else if (withMulliganReq) session.sendGre(opening, mulliganReq());
        else session.sendGre(opening);
        introSent = true;
        batching = true;       // mulligan stage: one diff per prompt (the opponent's mulligans included), like the real GRE
    }

    boolean isDisconnected() { return disconnected; }

    private GREToClientMessage.Builder mulliganReq() {
        GREToClientMessage.Builder m = req(GREMessageType.GREMessageType_MulliganReq);
        m.getMulliganReqBuilder().setMulliganType(com.wizards.mtga.gre.MulliganType.MulliganType_London).setFreeMulliganCount(0).setMulliganCount(mulligans);
        m.getPromptBuilder().setPromptId(PROMPT_MULLIGAN).addParameters(numberParam("NumberOfCards", 7));
        return m;
    }

    /** London mulligan: the client picks which cards go to the bottom of the library. */
    CardCollectionView askTuck(CardCollectionView hand, int count) {
        Map<Integer, Card> byId = new HashMap<>();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_GroupReq);
        com.wizards.mtga.gre.GroupReq.Builder gr = req.getGroupReqBuilder().setGroupType(com.wizards.mtga.gre.GroupType.GroupType_Ordered)
                .setContext(com.wizards.mtga.gre.GroupingContext.GroupingContext_LondonMulligan);
        for (Card c : hand) {
            byId.put(iid(c), c);
            gr.addInstanceIds(iid(c));
        }
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(hand.size() - count).setUpperBound(hand.size() - count)
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Hand).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_Top));
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(count).setUpperBound(count)
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Library).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_Bottom));
        System.out.println("[mtga] asking London mulligan bottom " + count + " of " + byId.keySet());
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_GroupResp));
        System.out.println("[mtga] client group reply " + r.getGroupResp().toString().replace("\n", " "));
        CardCollection bottom = new CardCollection();
        for (com.wizards.mtga.gre.Group g : r.getGroupResp().getGroupsList()) {
            if (g.getZoneType() != com.wizards.mtga.gre.ZoneType.ZoneType_Library) continue;
            for (int id : g.getIdsList()) {
                Card c = byId.get(id);
                if (c != null) bottom.add(c);
            }
        }
        return bottom;
    }

    /** Test aid (-Dmtga.rig=...): deterministic opening setups for the scripted regression recipes. */
    private void rigHand(Player p) {
        if ("dv".equals(System.getProperty("mtga.rig"))) {      // devourer deck: Wastes x7 in play, Devourer of Destiny in hand,
                                                                 // the AI's Grizzly Bears in play (a target for the cast trigger)
            List<String> mine = new ArrayList<>(java.util.Collections.nCopies(7, "Wastes"));
            mine.add("Devourer of Destiny");
            Player ai = opponent;
            for (String name : mine) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (name.startsWith("Devourer")) game.getAction().moveToHand(pick, null);
                else game.getAction().moveTo(ZoneType.Battlefield, pick, null, null);
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            for (Card c : ai.getCardsIn(ZoneType.Library)) {
                if (!c.getName().equals("Grizzly Bears")) continue;
                game.getAction().moveTo(ZoneType.Battlefield, c, null, null);
                System.out.println("[mtga] rig: AI Grizzly Bears instance " + iid(c));
                break;
            }
            return;
        }
        if ("block".equals(System.getProperty("mtga.rig"))) {      // my Elves vs the AI's Bears, both already in play
            for (Player pl : game.getPlayers()) {
                String name = pl == p ? "Llanowar Elves" : "Grizzly Bears";
                Card pick = null;
                for (Card c : pl.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick != null) {
                    game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                    System.out.println("[mtga] rig: battlefield " + name + " instance " + iid(pick));
                }
            }
            return;
        }
        for (Card c : new ArrayList<>(p.getCardsIn(ZoneType.Hand))) game.getAction().moveToLibrary(c, -1, null);
        String rig = System.getProperty("mtga.rig");
        if ("color".equals(rig)) {      // Room of Refuge alone in hand: playing it asks for a color
            for (Card c : p.getCardsIn(ZoneType.Library)) {
                if (c.getName().equals("Room of Refuge")) {
                    game.getAction().moveToHand(c, null);
                    System.out.println("[mtga] rig: Room of Refuge instance " + iid(c));
                    break;
                }
            }
            return;
        }
        if ("pt".equals(rig) || "ptk".equals(rig)) {      // playtest deck: Island + Mountain in play (ptk: 5 lands, kicker); shockland, Wilds, Brainstorm, Elves, Bonecrusher, Burst Lightning in hand
            List<String> names = new ArrayList<>(List.of("Island", "Mountain", "Breeding Pool", "Evolving Wilds", "Brainstorm", "Llanowar Elves", "Bonecrusher Giant", "Burst Lightning"));
            if ("ptk".equals(rig)) {
                names.addAll(0, List.of("Island", "Mountain", "Forest", "Command Tower"));
                names.add("Cultivate");
            }
            for (String name : names) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (List.of("Island", "Mountain", "Forest", "Command Tower").contains(name)) game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                else game.getAction().moveToHand(pick, null);
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("ptn".equals(rig)) {      // playtest deck: ninjutsu + dual-land colors. In play: Spirebluff Canal, Island, Mountain, Llanowar Elves, Birds (no sickness); hand: Ninja of the Deep Hours, Stormchaser's Talent
            for (String name : List.of("Spirebluff Canal", "Island", "Mountain", "Llanowar Elves", "Birds of Paradise", "Ninja of the Deep Hours", "Stormchaser's Talent")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (name.startsWith("Ninja") || name.startsWith("Stormchaser")) game.getAction().moveToHand(pick, null);
                else {
                    game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                    pick.setSickness(false);
                }
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("nc".equals(rig)) {      // newcards deck: Swamp, Forest, Grizzly Bears (no sickness) in play; Blade of the Bloodchief in hand
            for (String name : List.of("Swamp", "Forest", "Grizzly Bears", "Blade of the Bloodchief")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (name.startsWith("Blade")) game.getAction().moveToHand(pick, null);
                else {
                    game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                    pick.setSickness(false);
                }
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("nf".equals(rig)) {      // newfaces deck: Island x2, Forest, Mountain, Aberrant Researcher + Akki Lavarunner (no sickness) in
                                     // play; Beck // Call in hand; Lightning Bolt on top (upkeep mill -> Researcher transforms)
            // (a second Akki Lavarunner goes to the hand: the mulligan browser shows its rules text full size)
            for (String name : List.of("Island", "Island", "Forest", "Mountain", "Aberrant Researcher", "Akki Lavarunner", "Beck // Call", "Lightning Bolt", "Akki Lavarunner")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (name.startsWith("Beck") || p.getCardsIn(ZoneType.Battlefield).anyMatch(c -> c.getName().equals(name) && name.startsWith("Akki"))) {
                    game.getAction().moveToHand(pick, null);
                }
                else if (name.equals("Lightning Bolt")) game.getAction().moveToLibrary(pick, 0, null);
                else {
                    game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                    pick.setSickness(false);
                }
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("et".equals(rig)) {      // eldrazi deck: Eldrazi Temple, Wastes, Swamp in play; Kozilek's Command, Thought-Knot Seer, Dismember,
                                     // Ugin's Labyrinth (imprint) and Ulamog's Crusher (a card it can exile) in hand
            for (String name : List.of("Eldrazi Temple", "Wastes", "Swamp", "Kozilek's Command", "Thought-Knot Seer", "Dismember",
                    "Ugin's Labyrinth", "Ulamog's Crusher")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (List.of("Eldrazi Temple", "Wastes", "Swamp").contains(name)) game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                else game.getAction().moveToHand(pick, null);
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("sire".equals(rig)) {      // sire deck: Eldrazi Temple x2, Forest, Ugin's Labyrinth (Emrakul exiled with it) in play,
                                       // Sire of Seven Deaths in hand: {7} from 2+2+2+1 with auto payment
            Card lab = null;
            for (String name : List.of("Eldrazi Temple", "Eldrazi Temple", "Forest", "Ugin's Labyrinth", "Emrakul, the Aeons Torn", "Sire of Seven Deaths")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                if (name.startsWith("Sire")) game.getAction().moveToHand(pick, null);
                else if (name.startsWith("Emrakul")) {
                    Card exiled = game.getAction().exile(pick, null, null);
                    exiled.setExiledWith(lab);
                    exiled.setExiledBy(p);
                    lab.addExiledCard(exiled);
                }
                else {
                    // no ETB triggers: the imprint is set up by hand above
                    Card in = game.getAction().moveTo(ZoneType.Battlefield, pick, null, null);
                    if (name.equals("Ugin's Labyrinth")) lab = in;
                }
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        if ("pte".equals(rig)) {      // playtest deck: explore + blight. In play: Forest x2, Overgrown Tomb, Watery Grave, Llanowar Elves; hand: Jadelight Ranger, Requiting Hex
            for (String name : List.of("Forest", "Forest", "Overgrown Tomb", "Watery Grave", "Llanowar Elves", "Jadelight Ranger", "Requiting Hex")) {
                Card pick = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
                }
                if (pick == null) continue;
                boolean inPlay = !name.equals("Jadelight Ranger") && !name.equals("Requiting Hex");
                if (inPlay) game.getAction().moveToPlay(pick, null, forge.game.ability.AbilityKey.newMap());
                else game.getAction().moveToHand(pick, null);
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
            return;
        }
        boolean bolt = "bolt".equals(rig);
        String land = bolt ? "Mountain" : "Forest";
        if ("bf".equals(rig)) opponent.setLife(2, null);      // one Bears hit ends the game
        if ("bf".equals(rig) || bolt) {      // two untapped lands already in play
            for (int i = 0; i < 2; i++) {
                Card f = null;
                for (Card c : p.getCardsIn(ZoneType.Library)) {
                    if (c.getName().equals(land) && (f == null || c.getId() < f.getId())) f = c;
                }
                game.getAction().moveToPlay(f, null, forge.game.ability.AbilityKey.newMap());
                System.out.println("[mtga] rig: battlefield Forest instance " + iid(f));
            }
        }
        for (String name : bolt ? new String[] {"Lightning Bolt", "Lightning Bolt", "Mountain", "Lightning Bolt", "Lightning Bolt", "Lightning Bolt", "Lightning Bolt", "Lightning Bolt", "Lightning Bolt"} : new String[] {"Forest", "Llanowar Elves", "Forest", "Grizzly Bears", "Grizzly Bears", "Forest", "Grizzly Bears"}) {
            Card pick = null;
            for (Card c : p.getCardsIn(ZoneType.Library)) {
                if (c.getName().equals(name) && (pick == null || c.getId() < pick.getId())) pick = c;
            }
            if (pick != null) {
                game.getAction().moveToHand(pick, null);
                System.out.println("[mtga] rig: " + name + " instance " + iid(pick));
            }
        }
    }

    // ---- generic choices ---------------------------------------------------------------------------------------

    /** Generic "select N of these cards" prompt (discard, search, choose); returns the chosen cards (may be fewer than min on error). */
    CardCollection askSelectCards(List<Card> options, int min, int max, com.wizards.mtga.gre.SelectionContext context,
                                  int promptId, String promptParam, Card source) {
        Map<Integer, Card> byId = new HashMap<>();
        List<Card> hidden = new ArrayList<>();
        for (Card c : options) if (c.isInZone(ZoneType.Library) || (c.isInZone(ZoneType.Hand) && c.getOwner() != human)) hidden.add(c);
        sync.reveal(hidden);
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_SelectNReq);
        com.wizards.mtga.gre.SelectNReq.Builder sn = req.getSelectNReqBuilder().setMinSel(min).setMaxSel(max).setContext(context)
                .setListType(com.wizards.mtga.gre.SelectionListType.SelectionListType_Static)
                .setIdType(com.wizards.mtga.gre.IdType.IdType_InstanceId);
        if (source != null) sn.setSourceId(iid(source));
        for (Card c : options) {
            byId.put(iid(c), c);
            sn.addIds(iid(c));
        }
        if (promptId == 0) promptId = max == 2 ? PROMPT_CHOOSE_TWO : PROMPT_CHOOSE_CARD;      // the client NREs on a SelectNReq without prompt
        {
            com.wizards.mtga.gre.Prompt.Builder pb = sn.getPromptBuilder().setPromptId(promptId);
            req.getPromptBuilder().setPromptId(promptId);
            if (promptParam != null) {
                pb.addParameters(numberParam(promptParam, max));
                req.getPromptBuilder().addParameters(numberParam(promptParam, max));
            }
        }
        System.out.println("[mtga] asking select " + min + ".." + max + " of " + byId.keySet() + " ctx=" + context);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_SelectNResp, ClientMessageType.ClientMessageType_CancelActionReq));
        System.out.println("[mtga] client select reply " + r.toString().replace("\n", " "));
        CardCollection chosen = new CardCollection();
        for (int id : r.getSelectNResp().getIdsList()) {
            Card c = byId.get(id);
            if (c != null && !chosen.contains(c)) chosen.add(c);
        }
        sync.clearRevealed();
        return chosen;
    }

    /**
     * Non-mana cost choice ("return an unblocked attacker", "sacrifice a creature"...) the way the GRE asks it:
     * PayCostsReq{effectCostReq{Select, costSelection=SelectNReq(NonMana_Payment, OptionContext_Payment)}}, answered by
     * EffectCostResp{costSelection=SelectNResp} (or CancelActionReq). Empty result = cancelled.
     */
    CardCollection askCostSelection(List<Card> options, int amount, int promptId, Card source) {
        Map<Integer, Card> byId = new HashMap<>();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_PayCostsReq);
        com.wizards.mtga.gre.EffectCostReq.Builder ec = req.getPayCostsReqBuilder().getEffectCostReqBuilder()
                .setEffectCostType(com.wizards.mtga.gre.EffectCostType.EffectCostType_Select);
        com.wizards.mtga.gre.SelectNReq.Builder sn = ec.getCostSelectionBuilder().setMinSel(amount).setMaxSel(amount)
                .setContext(com.wizards.mtga.gre.SelectionContext.SelectionContext_NonMana_Payment)
                .setOptionContext(com.wizards.mtga.gre.OptionContext.OptionContext_Payment)
                .setListType(com.wizards.mtga.gre.SelectionListType.SelectionListType_Static)
                .setIdType(com.wizards.mtga.gre.IdType.IdType_InstanceId);
        if (source != null) sn.setSourceId(iid(source));
        for (Card c : options) {
            byId.put(iid(c), c);
            sn.addIds(iid(c));
        }
        if (promptId == 0) promptId = amount == 2 ? PROMPT_CHOOSE_TWO : PROMPT_CHOOSE_CARD;
        sn.getPromptBuilder().setPromptId(promptId);
        req.getPromptBuilder().setPromptId(promptId);
        System.out.println("[mtga] asking cost selection " + amount + " of " + byId.keySet() + " for " + source);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_EffectCostResp, ClientMessageType.ClientMessageType_SelectNResp,
                ClientMessageType.ClientMessageType_CancelActionReq));
        System.out.println("[mtga] client cost selection " + r.toString().replace('\n', ' '));
        List<Integer> ids = r.getType() == ClientMessageType.ClientMessageType_EffectCostResp ? r.getEffectCostResp().getCostSelection().getIdsList()
                : r.getType() == ClientMessageType.ClientMessageType_SelectNResp ? r.getSelectNResp().getIdsList() : List.of();
        CardCollection chosen = new CardCollection();
        for (int id : ids) {
            Card c = byId.get(id);
            if (c != null && !chosen.contains(c)) chosen.add(c);
        }
        return chosen;
    }

    /** Yes/no question (OptionalActionMessage: "Take action" / "Decline"). */
    boolean askOptional(Card source, int promptId, List<PromptParameter> params, String what) {
        return askOptional(source, promptId, params, what, 0);
    }

    /**
     * Yes/no question; replacementAbility (client ability id, e.g. a shockland's 90846) is published as a ReplacementEffect
     * persistent annotation on the source while asking, which makes the client use its special layouts
     * ("Pay 2 Life" / "Enters Tapped": ALT_Browser.OptionalAction.SpecialBrowserLayout is keyed by that ability id).
     */
    boolean askOptional(Card source, int promptId, List<PromptParameter> params, String what, int replacementAbility) {
        String reKey = null;
        if (replacementAbility != 0 && source != null) {
            reKey = "re:" + iid(source);
            sync.putPersistent(reKey, com.wizards.mtga.gre.AnnotationInfo.newBuilder().setAffectorId(iid(source)).addAffectedIds(iid(source))
                    .addType(com.wizards.mtga.gre.AnnotationType.AnnotationType_ReplacementEffect)
                    .addDetails(com.wizards.mtga.gre.KeyValuePairInfo.newBuilder().setKey("grpid")
                            .setType(com.wizards.mtga.gre.KeyValuePairValueType.KeyValuePairValueType_int32).addValueInt32(replacementAbility)));
        }
        try {
            return askOptionalNow(source, promptId, params, what);
        } finally {
            if (reKey != null) sync.removePersistent(reKey);
        }
    }

    private boolean askOptionalNow(Card source, int promptId, List<PromptParameter> params, String what) {
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_OptionalActionMessage);
        com.wizards.mtga.gre.OptionalActionMessage.Builder oa = req.getOptionalActionMessageBuilder();
        if (source != null) {
            oa.setSourceId(iid(source)).addHighlightIds(iid(source)).setHighlight(com.wizards.mtga.gre.HighlightType.HighlightType_Hot);
        }
        if (promptId != 0) {
            oa.getPromptBuilder().setPromptId(promptId).addAllParameters(params);
            req.getPromptBuilder().setPromptId(promptId).addAllParameters(params);
        }
        System.out.println("[mtga] asking optional (" + what + ") prompt " + promptId);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_OptionalActionResp, ClientMessageType.ClientMessageType_CancelActionReq));
        boolean yes = r.getType() == ClientMessageType.ClientMessageType_OptionalActionResp
                && r.getOptionalResp().getResponse() == com.wizards.mtga.gre.OptionResponse.OptionResponse_Allow_Yes;
        System.out.println("[mtga] optional answer " + yes);
        return yes;
    }

    /**
     * Explore with a nonland card: the client's Explore browser (drag to graveyard or keep on top). It is an
     * OptionalActionMessage tagged with CardMechanicType_Explore whose recipient is the revealed card (shown to the human
     * while asking); "Yes" = graveyard. Prompt 115 "Select whether to place {CardId} into your graveyard or back on top...".
     */
    boolean askExplore(Card explorer, Card revealed) {
        sync.reveal(List.of(revealed));     // sendRequest's diff shows the card to the human
        try {
            GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_OptionalActionMessage);
            com.wizards.mtga.gre.OptionalActionMessage.Builder oa = req.getOptionalActionMessageBuilder()
                    .addOptionalActionTypes(com.wizards.mtga.gre.CardMechanicType.CardMechanicType_Explore)
                    .addRecipientIds(iid(revealed));
            if (explorer != null) oa.setSourceId(iid(explorer));
            List<PromptParameter> params = List.of(numberParam("CardId", iid(revealed)));
            oa.getPromptBuilder().setPromptId(PROMPT_EXPLORE).addAllParameters(params);
            req.getPromptBuilder().setPromptId(PROMPT_EXPLORE).addAllParameters(params);
            System.out.println("[mtga] asking explore: " + revealed + " to graveyard?");
            sendRequest(req);
            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_OptionalActionResp, ClientMessageType.ClientMessageType_CancelActionReq));
            boolean yes = r.getType() == ClientMessageType.ClientMessageType_OptionalActionResp
                    && r.getOptionalResp().getResponse() == com.wizards.mtga.gre.OptionResponse.OptionResponse_Allow_Yes;
            System.out.println("[mtga] explore answer: graveyard=" + yes);
            return yes;
        } finally {
            sync.clearRevealed();
        }
    }

    /** Numeric choice (X costs, "choose a number"). */
    int askNumber(Card source, int min, int max) {
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_NumericInputReq);
        req.getNumericInputReqBuilder().setMinValue(Math.max(0, min)).setMaxValue(Math.max(min, max)).setStepSize(1)
                .setNumericInputType(com.wizards.mtga.gre.NumericInputType.NumericInputType_ChooseX);
        if (source != null) req.getNumericInputReqBuilder().setSourceId(iid(source));
        req.getPromptBuilder().setPromptId(PROMPT_X);
        System.out.println("[mtga] asking number " + min + ".." + max);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_NumericInputResp, ClientMessageType.ClientMessageType_CancelActionReq));
        if (r.getType() != ClientMessageType.ClientMessageType_NumericInputResp) return min;
        return Math.max(min, Math.min(max, r.getNumericInputResp().getNumericInputValue()));
    }

    /** Modal spells: the client shows the modes (ability grpIds) and returns the chosen ones. */
    List<AbilitySub> askModes(SpellAbility sa, List<AbilitySub> possible, int min, int num) {
        Card host = sa.getHostCard();
        int cardGrp = grp(host);
        List<Integer> childIds = new ArrayList<>();
        int modalAbility = 0;
        for (CardDb.Ability a : db.abilities(cardGrp)) {
            if (a.modalChildIds() == null || a.modalChildIds().isBlank()) continue;
            if (modalAbility == 0) modalAbility = a.id();
            for (String id : a.modalChildIds().split(",")) childIds.add(Integer.parseInt(id.trim()));
        }
        if (childIds.isEmpty()) return null;
        Map<Integer, AbilitySub> byGrp = new LinkedHashMap<>();
        for (AbilitySub sub : possible) {
            int best = 0;
            double bestScore = -1;
            for (int id : childIds) {
                CardDb.Ability a = db.ability(id);
                if (a == null || byGrp.containsKey(id)) continue;
                double s = textOverlap(sub.getDescription(), a.text(), host.getName());
                if (s > bestScore) { bestScore = s; best = id; }
            }
            if (best != 0) byGrp.put(best, sub);
        }
        if (byGrp.size() != possible.size()) return null;
        if (num > 1) {
            // "Choose two" (Kozilek's Command, ...): only the Modal casting-time option opens the client's multi-select
            // browser (ModalWorkflow: toggle modes, Done). A SelectNReq of ability ids opens SelectNWorkflow_Ability,
            // which submits the first mode clicked.
            GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_CastingTimeOptionsReq);
            com.wizards.mtga.gre.CastingTimeOptionReq.Builder o = com.wizards.mtga.gre.CastingTimeOptionReq.newBuilder().setCtoId(1)
                    .setCastingTimeOptionType(com.wizards.mtga.gre.CastingTimeOptionType.CastingTimeOptionType_Modal)
                    .setAffectorId(iid(host)).setAffectedId(iid(host)).setGrpId(cardGrp).setIsRequired(min > 0);
            o.getPromptBuilder().setPromptId(PROMPT_CHOOSE_MODES);
            com.wizards.mtga.gre.ModalReq.Builder modal = o.getModalReqBuilder().setAbilityGrpId(modalAbility).setMinSel(min).setMaxSel(num);
            for (int id : byGrp.keySet()) modal.addModalOptions(com.wizards.mtga.gre.ModalOption.newBuilder().setGrpId(id));
            // modes Forge rules out right now (no legal target...) are shown greyed out, as on Arena
            for (int id : childIds) if (!byGrp.containsKey(id)) modal.addExcludedOptions(com.wizards.mtga.gre.ModalOption.newBuilder().setGrpId(id));
            req.getCastingTimeOptionsReqBuilder().addCastingTimeOptionReq(o);
            System.out.println("[mtga] asking modes (" + min + "-" + num + ") " + byGrp.keySet() + " for " + host);
            sendRequest(req);
            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_CastingTimeOptionsResp, ClientMessageType.ClientMessageType_CancelActionReq));
            if (r.getType() != ClientMessageType.ClientMessageType_CastingTimeOptionsResp) return new ArrayList<>();
            List<com.wizards.mtga.gre.CastingTimeOptionResp> resps = new ArrayList<>(r.getCastingTimeOptionsResp().getCastingTimeOptionRespsList());
            if (r.getCastingTimeOptionsResp().hasCastingTimeOptionResp()) resps.add(r.getCastingTimeOptionsResp().getCastingTimeOptionResp());
            List<AbilitySub> out = new ArrayList<>();
            for (com.wizards.mtga.gre.CastingTimeOptionResp resp : resps) {
                for (int id : resp.getChooseModalResp().getGrpIdsList()) {
                    AbilitySub sub = byGrp.get(id);
                    if (sub != null && !out.contains(sub)) out.add(sub);
                }
            }
            return out;
        }
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_SelectNReq);
        com.wizards.mtga.gre.SelectNReq.Builder sn = req.getSelectNReqBuilder().setMinSel(min).setMaxSel(num)
                .setContext(com.wizards.mtga.gre.SelectionContext.SelectionContext_Modal)
                .setListType(com.wizards.mtga.gre.SelectionListType.SelectionListType_Dynamic)
                .setIdType(com.wizards.mtga.gre.IdType.IdType_AbilityGrpId).setSourceId(iid(host)).addAllIds(byGrp.keySet());
        sn.getPromptBuilder().setPromptId(PROMPT_CHOOSE_MODES);
        req.getPromptBuilder().setPromptId(PROMPT_CHOOSE_MODES);
        System.out.println("[mtga] asking modes " + byGrp.keySet() + " for " + host);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_SelectNResp, ClientMessageType.ClientMessageType_CancelActionReq));
        if (r.getType() != ClientMessageType.ClientMessageType_SelectNResp) return new ArrayList<>();
        List<AbilitySub> out = new ArrayList<>();
        for (int id : r.getSelectNResp().getIdsList()) {
            AbilitySub sub = byGrp.get(id);
            if (sub != null) out.add(sub);
        }
        return out;
    }

    /** The client's "Choose a color." prompt (Prompts table). */
    private static final int PROMPT_CHOOSE_COLOR = 118;

    /**
     * "Choose a color" (Room of Refuge, Thriving lands, Painter's Servant...): the client's color picker, a SelectNReq over the
     * static Colors list (always StaticSubset with the allowed CardColor values: plain Static shows Colorless as well), answered with CardColor values (Colorless 0,
     * White 1 .. Green 5). Returns Forge color masks (MagicColor.WHITE..., COLORLESS); empty if cancelled.
     */
    List<Byte> askColors(Card source, forge.card.ColorSet options, boolean colorless, int min, int max) {
        List<Byte> allowed = new ArrayList<>();
        for (byte c : forge.card.MagicColor.WUBRG) if (options.hasAnyColor(c)) allowed.add(c);
        if (colorless) allowed.add(forge.card.MagicColor.COLORLESS);
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_SelectNReq);
        com.wizards.mtga.gre.SelectNReq.Builder sn = req.getSelectNReqBuilder().setMinSel(min).setMaxSel(max)
                .setContext(com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution)
                .setListType(com.wizards.mtga.gre.SelectionListType.SelectionListType_StaticSubset)   // Static would offer Colorless too
                .setStaticList(com.wizards.mtga.gre.StaticList.StaticList_Colors);
        if (source != null) sn.setSourceId(iid(source));
        for (byte c : allowed) sn.addIds(cardColor(c));
        sn.getPromptBuilder().setPromptId(PROMPT_CHOOSE_COLOR);
        req.getPromptBuilder().setPromptId(PROMPT_CHOOSE_COLOR);
        System.out.println("[mtga] asking color " + min + ".." + max + " of " + sn.getIdsList() + " for " + source);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_SelectNResp, ClientMessageType.ClientMessageType_CancelActionReq));
        List<Byte> out = new ArrayList<>();
        if (r.getType() != ClientMessageType.ClientMessageType_SelectNResp) return out;
        for (int id : r.getSelectNResp().getIdsList()) {
            for (byte c : allowed) if (cardColor(c) == id && !out.contains(c)) out.add(c);
        }
        System.out.println("[mtga] client chose color " + r.getSelectNResp().getIdsList());
        return out;
    }

    /** GRE CardColor value of a Forge color mask (Colorless 0, White 1, Blue 2, Black 3, Red 4, Green 5). */
    private static int cardColor(byte mask) {
        for (int i = 0; i < forge.card.MagicColor.WUBRG.length; i++) if (forge.card.MagicColor.WUBRG[i] == mask) return i + 1;
        return 0;
    }

    private static double textOverlap(String a, String b, String cardName) {
        java.util.Set<String> x = words(a, cardName), y = words(b, cardName);
        if (x.isEmpty() || y.isEmpty()) return 0;
        int common = 0;
        for (String w : x) if (y.contains(w)) common++;
        return (double) common / (x.size() + y.size() - common);
    }

    private static java.util.Set<String> words(String s, String cardName) {
        if (s == null) return java.util.Set.of();
        String t = s.toLowerCase().replace("cardname", " ").replace(cardName.toLowerCase(), " ").replaceAll("\\{o([a-z0-9]+)\\}", "{$1}");
        java.util.Set<String> out = new java.util.HashSet<>(java.util.Arrays.asList(t.split("[^a-z0-9{}+/-]+")));
        out.remove("");
        return out;
    }

    /** Scry: GroupReq with top/bottom groups. */
    org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> askScry(CardCollection topN) {
        sync.reveal(topN);
        Map<Integer, Card> byId = new LinkedHashMap<>();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_GroupReq);
        com.wizards.mtga.gre.GroupReq.Builder gr = req.getGroupReqBuilder().setGroupType(com.wizards.mtga.gre.GroupType.GroupType_Ordered)
                .setContext(com.wizards.mtga.gre.GroupingContext.GroupingContext_Scry);
        for (Card c : topN) {
            byId.put(iid(c), c);
            gr.addInstanceIds(iid(c));
        }
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(0).setUpperBound(topN.size())
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Library).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_Top));
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(0).setUpperBound(topN.size())
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Library).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_Bottom));
        System.out.println("[mtga] asking scry " + byId.keySet());
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_GroupResp));
        System.out.println("[mtga] client scry reply " + r.getGroupResp().toString().replace("\n", " "));
        CardCollection top = new CardCollection(), bottom = new CardCollection();
        for (com.wizards.mtga.gre.Group g : r.getGroupResp().getGroupsList()) {
            boolean toBottom = g.getSubZoneType() == com.wizards.mtga.gre.SubZoneType.SubZoneType_Bottom;
            for (int id : g.getIdsList()) {
                Card c = byId.remove(id);
                if (c != null) (toBottom ? bottom : top).add(c);
            }
        }
        top.addAll(byId.values());      // anything the client did not place stays on top
        sync.clearRevealed();
        return org.apache.commons.lang3.tuple.ImmutablePair.of(top, bottom);
    }

    /** Surveil: GroupReq (Surveil context) with a Library/Top and a Graveyard group; the client answers [top, graveyard]. */
    org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> askSurveil(CardCollection topN) {
        sync.reveal(topN);
        Map<Integer, Card> byId = new LinkedHashMap<>();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_GroupReq);
        com.wizards.mtga.gre.GroupReq.Builder gr = req.getGroupReqBuilder().setGroupType(com.wizards.mtga.gre.GroupType.GroupType_Ordered)
                .setContext(com.wizards.mtga.gre.GroupingContext.GroupingContext_Surveil);
        for (Card c : topN) {
            byId.put(iid(c), c);
            gr.addInstanceIds(iid(c));
        }
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(0).setUpperBound(topN.size())
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Library).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_Top));
        gr.addGroupSpecs(com.wizards.mtga.gre.GroupSpecification.newBuilder().setLowerBound(0).setUpperBound(topN.size())
                .setZoneType(com.wizards.mtga.gre.ZoneType.ZoneType_Graveyard).setSubZoneType(com.wizards.mtga.gre.SubZoneType.SubZoneType_None));
        System.out.println("[mtga] asking surveil " + byId.keySet());
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_GroupResp));
        System.out.println("[mtga] client surveil reply " + r.getGroupResp().toString().replace("\n", " "));
        CardCollection top = new CardCollection(), grave = new CardCollection();
        for (com.wizards.mtga.gre.Group g : r.getGroupResp().getGroupsList()) {
            boolean toGrave = g.getZoneType() == com.wizards.mtga.gre.ZoneType.ZoneType_Graveyard;
            for (int id : g.getIdsList()) {
                Card c = byId.remove(id);
                if (c != null) (toGrave ? grave : top).add(c);
            }
        }
        top.addAll(byId.values());      // anything the client did not place stays on top
        sync.clearRevealed();
        return org.apache.commons.lang3.tuple.ImmutablePair.of(top, grave);
    }

    /**
     * "Look at" effects (Mishra's Bauble, ...): the human sees hidden cards in a selection browser, nothing is chosen.
     */
    void askLookAt(List<Card> cards, Card source) {
        askSelectCards(cards, 0, 1, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution, 0, null, source);
    }

    /**
     * Order cards (e.g. put back on top of the library in any order). The client answers nearest-the-end first (top first
     * for OrderingForTop); Forge moves the returned list in order, so the card that must end nearest the end goes last.
     */
    CardCollectionView askOrder(CardCollectionView cards, boolean forTop) {
        sync.reveal(cards);
        Map<Integer, Card> byId = new LinkedHashMap<>();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_OrderReq);
        for (Card c : cards) {
            byId.put(iid(c), c);
            req.getOrderReqBuilder().addIds(iid(c));
        }
        req.getOrderReqBuilder().setOrderingContext(forTop ? com.wizards.mtga.gre.OrderingContext.OrderingContext_OrderingForTop
                : com.wizards.mtga.gre.OrderingContext.OrderingContext_OrderingForBottom);
        req.getPromptBuilder().setPromptId(PROMPT_ORDER);
        System.out.println("[mtga] asking order " + byId.keySet());
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_OrderResp, ClientMessageType.ClientMessageType_CancelActionReq));
        sync.clearRevealed();
        if (r.getType() != ClientMessageType.ClientMessageType_OrderResp) return cards;
        CardCollection out = new CardCollection();
        for (int id : r.getOrderResp().getIdsList()) {
            Card c = byId.remove(id);
            if (c != null) out.add(c);
        }
        out.addAll(byId.values());
        java.util.Collections.reverse(out);
        return out;
    }

    // ---- priority ----------------------------------------------------------------------------------------------

    /** Priority for the human: returns the chosen ability, or null to pass. */
    List<SpellAbility> askPriority(Player me) {
        batching = false;
        PhaseType phase = game.getPhaseHandler().getPhase();
        boolean myTurn = game.getPhaseHandler().isPlayerTurn(me);
        boolean full = settings.fullControl();
        boolean stop = settings.stopAt(phase, myTurn) && game.getStack().isEmpty();
        boolean respond = !game.getStack().isEmpty();
        List<SpellAbility> playable = new ArrayList<>();
        for (Card c : me.getCardsIn(List.of(ZoneType.Hand, ZoneType.Battlefield, ZoneType.Graveyard, ZoneType.Exile, ZoneType.Command))) {
            for (SpellAbility sa : c.getAllPossibleAbilities(me, true)) {
                if (!sa.isManaAbility()) playable.add(sa);
            }
        }
        offered.clear();
        offeredTaps.clear();
        offeredTapColors.clear();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_ActionsAvailableReq);
        List<Action> fromOtherZones = new ArrayList<>();
        for (SpellAbility sa : playable) {
            Card host = sa.getHostCard();
            boolean canAfford = sa.isLandAbility() || affordable(sa, me);
            boolean elsewhere = !host.isInZone(ZoneType.Hand) && !host.isInZone(ZoneType.Battlefield);
            if (!canAfford && !elsewhere) continue;
            if (!elsewhere && !sa.isLandAbility() && !hasLegalTargets(sa)) continue;      // Dismember with no creature around
            ActionType at = sa.isLandAbility() ? ActionType.ActionType_Play : sa.isAdventure() ? ActionType.ActionType_CastAdventure
                    : sa.isSpell() ? ActionType.ActionType_Cast : ActionType.ActionType_Activate;
            int iid = iid(host);
            int abilityGrp = 0;
            if (at == ActionType.ActionType_Activate || (at == ActionType.ActionType_Cast && sa.getAlternativeCost() != null)) {
                abilityGrp = abilities.abilityId(sa, grp(host));
            }
            String key = iid + ":" + at + ":" + abilityGrp;
            if (offered.containsKey(key)) continue;
            Action.Builder ab = Action.newBuilder().setActionType(at).setGrpId(grp(host)).setInstanceId(iid);
            if (abilityGrp != 0) ab.setAbilityGrpId(abilityGrp);
            if (at != ActionType.ActionType_Play) {
                addManaCost(ab, sa, iid);
                // without an auto-tap solution the client treats a costed action as unaffordable: no playable highlight
                List<SpellAbility> taps = new ArrayList<>();
                Map<SpellAbility, String> tapColors = new java.util.IdentityHashMap<>();
                com.wizards.mtga.gre.AutoTapSolution sol = autoTap(sa, me, taps, tapColors);
                offeredTaps.put(key, taps);
                offeredTapColors.put(key, tapColors);
                if (sol != null) ab.setAutoTapSolution(sol);
                else if (sa.getPayCosts() != null && sa.getPayCosts().getTotalMana().getCMC() > 0) System.out.println("[mtga] no auto-tap solution for " + sa);
            }
            // castable from exile/graveyard (adventures, flashback): listed in the game state even when unaffordable,
            // so the client keeps the card next to the hand
            if (elsewhere) fromOtherZones.add(ab.build());
            if (!canAfford) continue;
            offered.put(key, sa);
            req.getActionsAvailableReqBuilder().addActions(ab);
        }
        // keep a card listed while it stays where it is (e.g. on an adventure during the opponent's turn, when timing hides it)
        otherZoneActions.keySet().removeIf(k -> {
            Card c = sync.card(k);
            return c == null || iid(c) != k || c.isInZone(ZoneType.Hand) || c.isInZone(ZoneType.Battlefield) || c.isInZone(ZoneType.Stack);
        });
        for (Action a : fromOtherZones) otherZoneActions.putIfAbsent(a.getInstanceId(), new ArrayList<>());
        for (Integer k : new ArrayList<>(otherZoneActions.keySet())) {
            List<Action> now = new ArrayList<>();
            for (Action a : fromOtherZones) if (a.getInstanceId() == k) now.add(a);
            if (!now.isEmpty()) otherZoneActions.put(k, now);
        }
        List<Action> hypothetical = new ArrayList<>();
        for (List<Action> l : otherZoneActions.values()) hypothetical.addAll(l);
        sync.setHypotheticalActions(hypothetical);
        // unforced: when the human just auto-passes, a priority-only change is not sent (it made every step blink
        // twice on the client's phase ladder); a prompt below sends the full decision-time diff with the request
        sendDiff(true, false);
        if (!full && settings.autoPassTurn()) return null;
        if (!full && settings.autoPassUnlessOpponentAction() && !respond) return null;
        if (!full && respond && settings.autoResolveStack()) return null;
        if (!full && (playable.isEmpty() || !(stop || respond))) return null;
        if (offered.isEmpty() && !full) return null;      // nothing to do but pass: do not bother the client
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {      // tap for mana by hand (floating mana)
            for (SpellAbility ma : c.getManaAbilities()) {
                ma.setActivatingPlayer(me);
                if (!ma.canPlay() || !producesMana(ma)) continue;      // e.g. Command Tower without a commander
                int iid = iid(c), abilityGrp = abilities.abilityId(ma, grp(c));
                String key = iid + ":" + ActionType.ActionType_Activate_Mana + ":" + abilityGrp;
                if (offered.containsKey(key)) continue;
                offered.put(key, ma);
                Action.Builder ab = Action.newBuilder().setActionType(ActionType.ActionType_Activate_Mana).setGrpId(grp(c)).setInstanceId(iid);
                if (abilityGrp != 0) ab.setAbilityGrpId(abilityGrp);
                addManaCost(ab, ma, iid);
                addManaOptions(ab, ma, iid, abilityGrp);
                req.getActionsAvailableReqBuilder().addActions(ab);
            }
        }
        req.getActionsAvailableReqBuilder().addActions(Action.newBuilder().setActionType(ActionType.ActionType_Pass));
        System.out.println("[mtga] turn " + game.getPhaseHandler().getTurn() + " " + phase + " offering " + offered.keySet());
        sendRequest(req);

        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_PerformActionResp, ClientMessageType.ClientMessageType_ConcedeReq));
        if (r.getType() == ClientMessageType.ClientMessageType_ConcedeReq) {
            me.concede();
            return null;
        }
        System.out.println("[mtga] client replied " + r.getPerformActionResp().toString().replace("\n", " "));
        for (Action a : r.getPerformActionResp().getActionsList()) {
            if (a.getActionType() == ActionType.ActionType_Pass) return null;
            SpellAbility sa = offered.get(a.getInstanceId() + ":" + a.getActionType() + ":" + a.getAbilityGrpId());
            if (sa == null) {      // tolerate a missing/odd abilityGrpId: first offer for that object and type
                for (Map.Entry<String, SpellAbility> e : offered.entrySet()) {
                    if (e.getKey().startsWith(a.getInstanceId() + ":" + a.getActionType() + ":")) { sa = e.getValue(); break; }
                }
            }
            if (sa != null && sa.isManaAbility()) {
                applyManaChoice(sa, a);
                forge.game.player.PlaySpellAbility.playSpellAbility(me.getController(), me, sa);
                return askPriority(me);      // mana floats in the pool; the player keeps priority
            }
            if (sa != null) {
                pendingTaps = offeredTaps.get(a.getInstanceId() + ":" + a.getActionType() + ":" + a.getAbilityGrpId());
                pendingTapColors = offeredTapColors.get(a.getInstanceId() + ":" + a.getActionType() + ":" + a.getAbilityGrpId());
                return List.of(sa);
            }
            System.out.println("[mtga] client chose an action we did not offer: " + a);
        }
        return null;
    }

    // ---- combat ------------------------------------------------------------------------------------------------

    void askAttackers(Player me, forge.game.combat.Combat combat) {
        GameEntity defender = null;
        for (GameEntity e : combat.getDefenders()) {
            if (e instanceof Player) { defender = e; break; }
        }
        if (defender == null) return;
        List<Card> candidates = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            if (forge.game.combat.CombatUtil.canAttack(c, defender)) candidates.add(c);
        }
        if (candidates.isEmpty()) return;
        List<Card> chosen = new ArrayList<>();
        System.out.println("[mtga] turn " + game.getPhaseHandler().getTurn() + " asking attackers among " + candidates.size());
        sendRequest(attackersReq(candidates, chosen));

        while (true) {
            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_DeclareAttackersResp, ClientMessageType.ClientMessageType_SubmitAttackersReq));
            System.out.println("[mtga] client combat message " + r.getType() + " " + r.toString().replace("\n", " "));
            if (r.getType() == ClientMessageType.ClientMessageType_DeclareAttackersResp) {
                // each response only lists the attackers the player just toggled (DeclareAttackerRequest.UpdateAttacker)
                if (r.getDeclareAttackersResp().getAutoDeclare()) { chosen.clear(); chosen.addAll(candidates); }
                for (com.wizards.mtga.gre.Attacker a : r.getDeclareAttackersResp().getSelectedAttackersList()) {
                    Card c = sync.card(a.getAttackerInstanceId());
                    if (c == null || !candidates.contains(c)) continue;
                    if (a.hasSelectedDamageRecipient()) { if (!chosen.contains(c)) chosen.add(c); }
                    else chosen.remove(c);
                }
                sendRequest(attackersReq(candidates, chosen));      // echo (also after "All Attack": it only selects); the client then submits
                continue;
            }
            break;
        }
        for (Card c : chosen) combat.addAttacker(c, defender);
        session.sendGre(req(GREMessageType.GREMessageType_SubmitAttackersResp));
    }

    private GREToClientMessage.Builder attackersReq(List<Card> candidates, List<Card> chosen) {
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_DeclareAttackersReq);
        for (Card c : candidates) {
            com.wizards.mtga.gre.Attacker.Builder a = attackerOf(c).toBuilder();
            if (chosen.contains(c)) a.setSelectedDamageRecipient(a.getLegalDamageRecipients(0));
            req.getDeclareAttackersReqBuilder().addAttackers(a).addQualifiedAttackers(attackerOf(c));
        }
        req.getDeclareAttackersReqBuilder().setCanSubmitAttackers(true);
        return req;
    }

    private com.wizards.mtga.gre.Attacker attackerOf(Card c) {
        com.wizards.mtga.gre.DamageRecipient dr = com.wizards.mtga.gre.DamageRecipient.newBuilder()
                .setType(com.wizards.mtga.gre.DamageRecType.DamageRecType_Player).setPlayerSystemSeatId(StateSync.AI_SEAT).build();
        return com.wizards.mtga.gre.Attacker.newBuilder().setAttackerInstanceId(iid(c)).addLegalDamageRecipients(dr).build();
    }

    private GREToClientMessage.Builder blockersReq(List<Card> blockers, List<Card> attackers, Map<Card, Card> assignment) {
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_DeclareBlockersReq);
        for (Card b : blockers) {
            com.wizards.mtga.gre.Blocker.Builder bb = com.wizards.mtga.gre.Blocker.newBuilder().setBlockerInstanceId(iid(b))
                    .setMinAttackers(0).setMaxAttackers(1);
            for (Card a : attackers) {
                if (forge.game.combat.CombatUtil.canBlock(a, b)) bb.addAttackerInstanceIds(iid(a));
            }
            Card chosen = assignment.get(b);
            if (chosen != null) bb.addSelectedAttackerInstanceIds(iid(chosen));
            req.getDeclareBlockersReqBuilder().addBlockers(bb);
        }
        return req;
    }

    void askBlockers(Player me, forge.game.combat.Combat combat) {
        List<Card> attackers = new ArrayList<>(combat.getAttackers());
        if (attackers.isEmpty()) return;
        List<Card> blockers = new ArrayList<>();
        for (Card c : me.getCreaturesInPlay()) {
            for (Card a : attackers) {
                if (forge.game.combat.CombatUtil.canBlock(a, c)) { blockers.add(c); break; }
            }
        }
        if (blockers.isEmpty()) return;
        Map<Card, Card> assignment = new HashMap<>();   // blocker -> attacker
        System.out.println("[mtga] turn " + game.getPhaseHandler().getTurn() + " asking blockers among " + blockers.size());
        sendRequest(blockersReq(blockers, attackers, assignment));

        while (true) {
            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_DeclareBlockersResp, ClientMessageType.ClientMessageType_SubmitBlockersReq));
            System.out.println("[mtga] client combat message " + r.getType() + " " + r.toString().replace("\n", " "));
            if (r.getType() == ClientMessageType.ClientMessageType_DeclareBlockersResp) {
                for (com.wizards.mtga.gre.Blocker b : r.getDeclareBlockersResp().getSelectedBlockersList()) {
                    Card blocker = sync.card(b.getBlockerInstanceId());
                    if (blocker == null) continue;
                    assignment.remove(blocker);
                    for (int aid : b.getSelectedAttackerInstanceIdsList()) {
                        Card attacker = sync.card(aid);
                        if (attacker != null && attackers.contains(attacker)) assignment.put(blocker, attacker);
                    }
                }
                // the client locks its UI until the server echoes the selection back
                sendRequest(blockersReq(blockers, attackers, assignment));
                continue;
            }
            break;
        }
        for (Map.Entry<Card, Card> e : assignment.entrySet()) combat.addBlocker(e.getValue(), e.getKey());
        session.sendGre(req(GREMessageType.GREMessageType_SubmitBlockersResp));
    }

    // ---- targeting ---------------------------------------------------------------------------------------------

    /** Ask the client to choose targets for sa (single target slot); false if cancelled / impossible. */
    boolean askTargets(SpellAbility sa) {
        forge.game.spellability.TargetRestrictions tgt = sa.getTargetRestrictions();
        if (tgt == null) return true;
        Card host = sa.getHostCard();
        int min = tgt.getMinTargets(host, sa);
        int max = tgt.getMaxTargets(host, sa);
        List<GameEntity> candidates = tgt.getAllCandidates(sa, false);
        List<forge.game.spellability.SpellAbilityStackInstance> stackCandidates = new ArrayList<>();
        if (tgt.getZone() != null && tgt.getZone().contains(ZoneType.Stack)) {
            for (forge.game.spellability.SpellAbilityStackInstance si : game.getStack()) {
                if (sa.canTargetSpellAbility(si.getSpellAbility())) stackCandidates.add(si);
            }
        }
        if (candidates.isEmpty() && stackCandidates.isEmpty()) return min == 0;
        if (max <= 0) return min <= 0;      // "up to X targets" with X = 0 (Kozilek's Command): nothing to choose

        Map<Integer, Object> byId = new LinkedHashMap<>();
        for (GameEntity e : candidates) {
            int id = e instanceof Player ? StateSync.seat((Player) e, human) : iid((Card) e);
            byId.put(id, e);
        }
        for (forge.game.spellability.SpellAbilityStackInstance si : stackCandidates) {
            int id = sync.stackIid(si);
            if (id != 0) byId.put(id, si.getSpellAbility());
        }
        // a trigger whose targets are forced (Soul-Guide Lantern entering with only the sacrificed Urza's Saga in a
        // graveyard) is not a question: take them, as Arena does
        if (sa.getRootAbility().isTrigger() && min >= 1 && byId.size() == min) {
            System.out.println("[mtga] forced targets for " + sa + ": " + byId.keySet());
            for (Object e : byId.values()) {
                if (e instanceof GameEntity ge) sa.getTargets().add(ge);
                else if (e instanceof SpellAbility tsa) sa.getTargets().add(tsa);
            }
            return true;
        }
        System.out.println("[mtga] asking targets for " + sa + " among " + byId.keySet() + " min=" + min + " max=" + max);
        int abilityGrp = sa.isSpell() ? 0 : abilities.abilityId(sa.getRootAbility(), grp(host));
        List<Object> chosen = new ArrayList<>();
        sendRequest(targetsReq(byId, chosen, min, max, host, abilityGrp));
        while (true) {
            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_SelectTargetsResp,
                    ClientMessageType.ClientMessageType_SubmitTargetsReq, ClientMessageType.ClientMessageType_CancelActionReq));
            System.out.println("[mtga] client target message " + r.getType() + " " + r.toString().replace("\n", " "));
            if (r.getType() == ClientMessageType.ClientMessageType_CancelActionReq) return false;
            if (r.getType() == ClientMessageType.ClientMessageType_SubmitTargetsReq && chosen.size() >= min) break;
            if (r.getType() == ClientMessageType.ClientMessageType_SelectTargetsResp) {
                // each response toggles the clicked target; the echo marks picks as Unselect (that draws the arrow) and
                // the client submits by itself once the selection is complete
                for (com.wizards.mtga.gre.Target t : r.getSelectTargetsResp().getTarget().getTargetsList()) {
                    Object e = byId.get(t.getTargetInstanceId());
                    if (e == null) continue;
                    if (chosen.contains(e)) chosen.remove(e);
                    else if (chosen.size() < max) chosen.add(e);
                }
            }
            sendRequest(targetsReq(byId, chosen, min, max, host, abilityGrp));
        }
        for (Object e : chosen) {
            if (e instanceof GameEntity ge) sa.getTargets().add(ge);
            else if (e instanceof SpellAbility tsa) sa.getTargets().add(tsa);
        }
        session.sendGre(req(GREMessageType.GREMessageType_SubmitTargetsResp));
        return true;
    }

    private GREToClientMessage.Builder targetsReq(Map<Integer, Object> byId, List<Object> chosen, int min, int max, Card host, int abilityGrp) {
        com.wizards.mtga.gre.TargetSelection.Builder sel = com.wizards.mtga.gre.TargetSelection.newBuilder()
                .setTargetIdx(1).setMinTargets(min).setMaxTargets(max).setSelectedTargets(chosen.size());
        for (Map.Entry<Integer, Object> e : byId.entrySet()) {
            sel.addTargets(com.wizards.mtga.gre.Target.newBuilder().setTargetInstanceId(e.getKey())
                    .setLegalAction(chosen.contains(e.getValue()) ? com.wizards.mtga.gre.SelectAction.SelectAction_Unselect
                            : com.wizards.mtga.gre.SelectAction.SelectAction_Select));
        }
        sel.getPromptBuilder().setPromptId(PROMPT_TARGETS);
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_SelectTargetsReq);
        req.getPromptBuilder().setPromptId(PROMPT_TARGETS);
        req.getSelectTargetsReqBuilder().addTargets(sel).setSourceId(iid(host));
        if (abilityGrp != 0) req.getSelectTargetsReqBuilder().setAbilityGrpId(abilityGrp);
        return req;
    }

    /** Kicker and other optional additional costs: CastingTimeOptionsReq (one option per cost + Done). */
    List<forge.game.spellability.OptionalCostValue> askOptionalCosts(SpellAbility sa, List<forge.game.spellability.OptionalCostValue> options) {
        Card host = sa.getHostCard();
        GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_CastingTimeOptionsReq);
        Map<Integer, forge.game.spellability.OptionalCostValue> byCto = new HashMap<>();
        int cto = 0;
        for (forge.game.spellability.OptionalCostValue opt : options) {
            if (!sa.canPlayWithOptionalCost(opt)) continue;
            boolean kicker = opt.getType() == forge.game.spellability.OptionalCost.Kicker1 || opt.getType() == forge.game.spellability.OptionalCost.Kicker2;
            // The client has no UI for CastingTimeOptionType_OptionalCost (CastingTimeOptionRequest throws
            // NotImplementedException and the duel crashes): kicker uses the Kicker buttons, every other optional
            // additional cost ("you may blight 1", ...) the AdditionalCost browser, keyed by the card's own
            // additional-cost ability (Abilities.Category 5).
            int abilityId = abilities.abilityByText(grp(host), opt.getType().getName() + " " + opt.getCost().toSimpleString(), host);
            if (!kicker) {
                for (CardDb.Ability a : db.abilities(grp(host))) {
                    if (a.category() == 5) { abilityId = a.id(); break; }
                }
            }
            com.wizards.mtga.gre.CastingTimeOptionReq.Builder o = com.wizards.mtga.gre.CastingTimeOptionReq.newBuilder().setCtoId(++cto)
                    .setCastingTimeOptionType(kicker ? com.wizards.mtga.gre.CastingTimeOptionType.CastingTimeOptionType_Kicker
                            : com.wizards.mtga.gre.CastingTimeOptionType.CastingTimeOptionType_AdditionalCost)
                    .setAffectorId(iid(host)).setAffectedId(iid(host)).setPlayerIdToPrompt(StateSync.HUMAN_SEAT)
                    .setGrpId(abilityId);
            if (opt.getCost().hasManaCost()) o.addAllManaCost(manaRequirements(opt.getCost().getTotalMana(), iid(host)));
            req.getCastingTimeOptionsReqBuilder().addCastingTimeOptionReq(o);
            byCto.put(cto, opt);
        }
        if (byCto.isEmpty()) return List.of();
        req.getCastingTimeOptionsReqBuilder().addCastingTimeOptionReq(com.wizards.mtga.gre.CastingTimeOptionReq.newBuilder().setCtoId(++cto)
                .setCastingTimeOptionType(com.wizards.mtga.gre.CastingTimeOptionType.CastingTimeOptionType_Done)
                .setAffectorId(iid(host)).setAffectedId(iid(host)).setPlayerIdToPrompt(StateSync.HUMAN_SEAT));
        System.out.println("[mtga] asking optional costs " + byCto.values() + " for " + host);
        sendRequest(req);
        ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_CastingTimeOptionsResp, ClientMessageType.ClientMessageType_CancelActionReq));
        System.out.println("[mtga] client casting-time option " + r.toString().replace("\n", " "));
        List<forge.game.spellability.OptionalCostValue> chosen = new ArrayList<>();
        if (r.getType() != ClientMessageType.ClientMessageType_CastingTimeOptionsResp) return chosen;
        List<com.wizards.mtga.gre.CastingTimeOptionResp> resps = new ArrayList<>(r.getCastingTimeOptionsResp().getCastingTimeOptionRespsList());
        if (r.getCastingTimeOptionsResp().hasCastingTimeOptionResp()) resps.add(r.getCastingTimeOptionsResp().getCastingTimeOptionResp());
        for (com.wizards.mtga.gre.CastingTimeOptionResp resp : resps) {
            forge.game.spellability.OptionalCostValue opt = byCto.get(resp.getCtoId());
            if (opt != null && !chosen.contains(opt)) chosen.add(opt);
        }
        return chosen;
    }

    // ---- manual mana payment -----------------------------------------------------------------------------------

    /** Interactive payment (client full control / manual mana): the client taps sources one by one. */
    boolean askManaPayment(forge.game.mana.ManaCostBeingPaid toPay, SpellAbility sa, Player me, forge.game.player.PlayerController ctl) {
        // floating mana pays first, as Forge's own payment input does (with every source already tapped for floating
        // mana, Kozilek's Command X=2 with {G}{G}{C}{C} in the pool found no source to offer and the cast failed)
        if (!toPay.isPaid()) me.getManaPool().payManaCostFromPool(toPay, sa, false, sa.getPayingMana());
        // Phyrexian symbols no untapped source can make the color of: 2 life each (Forge's own input has the player
        // click themselves for that); the life is paid only once the whole cost is
        int phyLife = 0;
        while (true) {
            while (toPay.containsPhyrexianMana() && !phyrexianPayableWithMana(toPay, sa, me)
                    && me.canPayLife(phyLife + 2, false, sa) && toPay.payPhyrexian()) {
                phyLife += 2;
                sa.setSpendPhyrexianMana(true);
            }
            if (toPay.isPaid()) break;
            byte canUse = 0;
            for (byte color : forge.card.mana.ManaAtom.MANATYPES) {
                if (toPay.isAnyPartPayableWith(color, me.getManaPool())) canUse |= color;
            }
            if (toPay.isAnyPartPayableWith((byte) forge.card.mana.ManaAtom.GENERIC, me.getManaPool())) canUse |= forge.card.mana.ManaAtom.GENERIC;

            // every usable mana ability of every source (Eldrazi Temple: {C} and the Eldrazi-only {C}{C}), keyed
            // "instance:abilityGrp" -- the reply names both
            Map<String, SpellAbility> sources = new LinkedHashMap<>();
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility ma : c.getManaAbilities()) {
                    ma.setActivatingPlayer(me);
                    if (ma.canPlay(true) && ma.isManaAbilityFor(sa, canUse)) sources.putIfAbsent(iid(c) + ":" + abilities.abilityId(ma, grp(c)), ma);
                }
            }
            if (sources.isEmpty()) return false;

            GREToClientMessage.Builder req = req(GREMessageType.GREMessageType_PayCostsReq);
            int host = iid(sa.getHostCard());
            req.getPayCostsReqBuilder().addAllManaCost(manaRequirements(toPay.toManaCost(), host));
            for (SpellAbility ma : sources.values()) {
                Card src = ma.getHostCard();
                int srcIid = iid(src);
                Action.Builder ab = Action.newBuilder().setActionType(ActionType.ActionType_Activate_Mana).setGrpId(grp(src)).setInstanceId(srcIid);
                int abilityGrp = abilities.abilityId(ma, grp(src));
                if (abilityGrp != 0) ab.setAbilityGrpId(abilityGrp);
                addManaOptions(ab, ma, srcIid, abilityGrp);
                req.getPayCostsReqBuilder().getPaymentActionsBuilder().addActions(ab);
            }
            System.out.println("[mtga] manual payment for " + sa.getHostCard() + ": unpaid " + toPay + " sources " + sources.keySet());
            sendRequest(req);

            ClientToGREMessage r = await(m -> is(m, ClientMessageType.ClientMessageType_PerformActionResp, ClientMessageType.ClientMessageType_CancelActionReq));
            System.out.println("[mtga] client payment message " + r.getType() + " " + r.toString().replace("\n", " "));
            if (r.getType() == ClientMessageType.ClientMessageType_CancelActionReq) return false;
            boolean progressed = false;
            for (Action a : r.getPerformActionResp().getActionsList()) {
                SpellAbility chosen = sources.get(a.getInstanceId() + ":" + a.getAbilityGrpId());
                if (chosen == null) {      // reply without (or with another) ability id: that source's first ability
                    for (Map.Entry<String, SpellAbility> e : sources.entrySet()) {
                        if (e.getKey().startsWith(a.getInstanceId() + ":")) { chosen = e.getValue(); break; }
                    }
                }
                if (chosen == null) continue;
                applyManaChoice(chosen, a);
                if (forge.game.player.PlaySpellAbility.playSpellAbility(ctl, me, chosen)) {
                    me.getManaPool().payManaFromAbility(sa, toPay, chosen);
                    progressed = true;
                }
            }
            if (!progressed) System.out.println("[mtga] payment reply did not tap anything we offered");
        }
        if (phyLife > 0) me.payLife(phyLife, sa, false);
        sendDiff(true);
        return true;
    }

    /** Can an untapped mana source make the color of one of the unpaid Phyrexian symbols? */
    private static boolean phyrexianPayableWithMana(forge.game.mana.ManaCostBeingPaid toPay, SpellAbility sa, Player me) {
        for (forge.card.mana.ManaCostShard shard : toPay.getUnpaidShards()) {
            if (!shard.isPhyrexian()) continue;
            for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
                for (SpellAbility ma : c.getManaAbilities()) {
                    ma.setActivatingPlayer(me);
                    if (!ma.canPlay(true) || ma.getManaPart() == null) continue;
                    for (int i = 0; i < COLOR_CODES.length; i++) {
                        if ((shard.getColorMask() & COLOR_MASKS[i]) != 0 && ma.getManaPart().canProduce(COLOR_CODES[i], ma)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static List<com.wizards.mtga.gre.ManaRequirement> manaRequirements(forge.card.mana.ManaCost mc, int objectId) {
        List<com.wizards.mtga.gre.ManaRequirement> out = new ArrayList<>();
        if (mc == null || mc.isNoCost()) return out;
        int generic = mc.getGenericCost();
        if (generic > 0) {
            out.add(com.wizards.mtga.gre.ManaRequirement.newBuilder()
                    .addColor(com.wizards.mtga.gre.ManaColor.ManaColor_Generic).setCount(generic).setObjectId(objectId).build());
        }
        Map<com.wizards.mtga.gre.ManaColor, Integer> counts = new java.util.EnumMap<>(com.wizards.mtga.gre.ManaColor.class);
        for (forge.card.mana.ManaCostShard shard : mc) {
            int mask = shard.getColorMask();
            com.wizards.mtga.gre.ManaColor c = (mask & forge.card.MagicColor.WHITE) != 0 ? com.wizards.mtga.gre.ManaColor.ManaColor_White
                    : (mask & forge.card.MagicColor.BLUE) != 0 ? com.wizards.mtga.gre.ManaColor.ManaColor_Blue
                    : (mask & forge.card.MagicColor.BLACK) != 0 ? com.wizards.mtga.gre.ManaColor.ManaColor_Black
                    : (mask & forge.card.MagicColor.RED) != 0 ? com.wizards.mtga.gre.ManaColor.ManaColor_Red
                    : (mask & forge.card.MagicColor.GREEN) != 0 ? com.wizards.mtga.gre.ManaColor.ManaColor_Green
                    : shard == forge.card.mana.ManaCostShard.COLORLESS ? com.wizards.mtga.gre.ManaColor.ManaColor_Colorless
                    : shard == forge.card.mana.ManaCostShard.X ? com.wizards.mtga.gre.ManaColor.ManaColor_X : null;
            if (c != null) counts.merge(c, 1, Integer::sum);
        }
        for (Map.Entry<com.wizards.mtga.gre.ManaColor, Integer> e : counts.entrySet()) {
            out.add(com.wizards.mtga.gre.ManaRequirement.newBuilder().addColor(e.getKey()).setCount(e.getValue()).setObjectId(objectId).build());
        }
        return out;
    }

    private static final String[] COLOR_CODES = {"W", "U", "B", "R", "G"};
    private static final com.wizards.mtga.gre.ManaColor[] COLOR_ENUMS = {com.wizards.mtga.gre.ManaColor.ManaColor_White,
            com.wizards.mtga.gre.ManaColor.ManaColor_Blue, com.wizards.mtga.gre.ManaColor.ManaColor_Black,
            com.wizards.mtga.gre.ManaColor.ManaColor_Red, com.wizards.mtga.gre.ManaColor.ManaColor_Green};
    private static final int[] COLOR_MASKS = {forge.card.MagicColor.WHITE, forge.card.MagicColor.BLUE, forge.card.MagicColor.BLACK,
            forge.card.MagicColor.RED, forge.card.MagicColor.GREEN};

    /**
     * Which untapped sources would pay sa's mana cost (greedy: colored shards first, most constrained source first).
     * The client highlights a costed action as playable, and previews the taps on hover, only when this is present.
     */
    com.wizards.mtga.gre.AutoTapSolution autoTap(SpellAbility sa, Player me, List<SpellAbility> chosenSources) {
        return autoTap(sa, me, chosenSources, new java.util.IdentityHashMap<>());
    }

    com.wizards.mtga.gre.AutoTapSolution autoTap(SpellAbility sa, Player me, List<SpellAbility> chosenSources, Map<SpellAbility, String> colors) {
        forge.card.mana.ManaCost cost = sa.getPayCosts() == null ? null : sa.getPayCosts().getTotalMana();
        if (cost == null || cost.isNoCost() || cost.getCMC() == 0) return null;
        boolean tapsHost = sa.getPayCosts().hasTapCost();
        Map<Card, List<SpellAbility>> sources = new LinkedHashMap<>();      // untapped source -> its free mana abilities
        for (Card c : me.getCardsIn(ZoneType.Battlefield)) {
            if (c.isTapped() || (tapsHost && c == sa.getHostCard())) continue;
            for (SpellAbility ma : c.getManaAbilities()) {
                ma.setActivatingPlayer(me);
                // restricted mana (Eldrazi Temple's {C}{C}: colorless Eldrazi only) is only a source for what it may pay
                boolean allowed = ma.getManaPart() == null || ma.getManaPart().meetsManaRestrictions(sa);
                if (allowed && ma.canPlay() && producesMana(ma) && (ma.getPayCosts() == null || ma.getPayCosts().getTotalMana().getCMC() == 0)) {
                    sources.computeIfAbsent(c, k -> new ArrayList<>()).add(ma);
                }
            }
        }
        List<Card> free = new ArrayList<>(sources.keySet());
        List<com.wizards.mtga.gre.AutoTapAction> taps = new ArrayList<>();
        List<forge.card.mana.ManaCostShard> colored = new ArrayList<>();
        int colorless = 0;      // {C}: only colorless mana pays it
        for (forge.card.mana.ManaCostShard shard : cost) {
            if (shard.getColorMask() != 0) colored.add(shard);
            else if (shard == forge.card.mana.ManaCostShard.COLORLESS) colorless++;
        }
        List<StateSync.PoolMana> pool = new ArrayList<>();
        for (StateSync.PoolMana pm : sync.pool(me)) if (pm.mana().meetsManaRestrictions(sa)) pool.add(pm);
        int generic = cost.getGenericCost();
        for (java.util.Iterator<forge.card.mana.ManaCostShard> it = colored.iterator(); it.hasNext(); ) {      // floating mana pays first
            forge.card.mana.ManaCostShard shard = it.next();
            for (StateSync.PoolMana pm : pool) {
                if ((shard.getColorMask() & pm.mana().getColor()) == 0) continue;
                taps.add(com.wizards.mtga.gre.AutoTapAction.newBuilder().setManaId(pm.id()).build());
                pool.remove(pm);
                it.remove();
                break;
            }
        }
        for (java.util.Iterator<StateSync.PoolMana> it = pool.iterator(); colorless > 0 && it.hasNext(); ) {
            StateSync.PoolMana pm = it.next();
            if (!pm.mana().isColorless()) continue;
            taps.add(com.wizards.mtga.gre.AutoTapAction.newBuilder().setManaId(pm.id()).build());
            it.remove();
            colorless--;
        }
        while (generic > 0 && !pool.isEmpty()) {
            taps.add(com.wizards.mtga.gre.AutoTapAction.newBuilder().setManaId(pool.remove(0).id()).build());
            generic--;
        }
        int missing = 0;
        for (forge.card.mana.ManaCostShard shard : colored) {
            Card best = null;
            SpellAbility bestMa = null;
            int bestColors = 99, bestColor = -1;
            for (Card c : free) {
                int n = colorCount(sources.get(c));
                if (n >= bestColors) continue;
                for (SpellAbility ma : sources.get(c)) {
                    int pick = -1;
                    for (int i = 0; i < 5 && pick < 0; i++) {
                        if ((shard.getColorMask() & COLOR_MASKS[i]) != 0 && ma.getManaPart().canProduce(COLOR_CODES[i], ma)) pick = i;
                    }
                    if (pick >= 0) { best = c; bestMa = ma; bestColors = n; bestColor = pick; break; }
                }
            }
            if (best == null) { missing++; continue; }      // Forge says it is affordable (pool mana, odd sources): keep a partial preview
            free.remove(best);
            taps.add(tapAction(best, bestMa, COLOR_ENUMS[bestColor]));
            chosenSources.add(bestMa);
            colors.put(bestMa, COLOR_CODES[bestColor]);
        }
        free.sort(java.util.Comparator.comparingInt(c -> colorCount(sources.get(c))));      // spend the least flexible sources first
        while (colorless > 0) {      // {C} shards: the source making the most colorless mana (Eldrazi Temple {C}{C} pays two)
            Card pick = null;
            SpellAbility pickMa = null;
            int pickAmount = 0;
            for (Card c : free) {
                for (SpellAbility ma : sources.get(c)) {
                    int amount = ma.getManaPart() != null && ma.getManaPart().canProduce("C", ma) ? Math.max(1, ma.amountOfManaGenerated(false)) : 0;
                    if (amount > pickAmount) { pick = c; pickMa = ma; pickAmount = amount; }
                }
            }
            if (pick == null) break;      // partial preview, as for colored shards
            free.remove(pick);
            taps.add(tapAction(pick, pickMa, com.wizards.mtga.gre.ManaColor.ManaColor_Colorless));
            chosenSources.add(pickMa);
            if (pickAmount > colorless) generic = Math.max(0, generic - (pickAmount - colorless));      // the rest pays generic
            colorless -= pickAmount;
        }
        while (generic > 0) {      // generic: each source's biggest ability (Eldrazi Temple {C}{C} pays two of a {7})
            Card pick = null;
            SpellAbility pickMa = null;
            int pickAmount = 0;
            for (Card c : free) {
                for (SpellAbility ma : sources.get(c)) {
                    int amount = Math.max(1, ma.amountOfManaGenerated(false));
                    // the biggest that still fits, else the smallest overpay; ties keep the least flexible source
                    boolean better = pick == null || (amount <= generic
                            ? pickAmount > generic || amount > pickAmount
                            : pickAmount > generic && amount < pickAmount);
                    if (better) { pick = c; pickMa = ma; pickAmount = amount; }
                }
            }
            if (pick == null) break;
            free.remove(pick);
            taps.add(tapAction(pick, pickMa, com.wizards.mtga.gre.ManaColor.ManaColor_Generic));
            chosenSources.add(pickMa);
            generic -= pickAmount;
        }
        // a preview that does not cover the cost is only shown: pre-tapping it left Forge's payer with nothing
        // untapped (Sire of Seven Deaths tapped 4 for {7} and was not cast), so Forge picks the sources itself
        if (missing > 0 || colorless > 0 || generic > 0) chosenSources.clear();
        if (taps.isEmpty()) return null;
        return com.wizards.mtga.gre.AutoTapSolution.newBuilder().addAllAutoTapActions(taps).build();
    }

    /** Whether a mana ability would add any mana now (Command Tower without a commander adds none). */
    private static boolean producesMana(SpellAbility ma) {
        if (ma.getManaPart() == null) return true;
        for (String code : new String[] {"W", "U", "B", "R", "G", "C"}) if (ma.getManaPart().canProduce(code, ma)) return true;
        return false;
    }

    /** Number of colors a source can make with any of its mana abilities. */
    private static int colorCount(List<SpellAbility> mas) {
        int n = 0;
        for (String code : COLOR_CODES) {
            for (SpellAbility ma : mas) if (ma.getManaPart().canProduce(code, ma)) { n++; break; }
        }
        return n;
    }

    private com.wizards.mtga.gre.AutoTapAction tapAction(Card c, SpellAbility ma, com.wizards.mtga.gre.ManaColor color) {
        com.wizards.mtga.gre.AutoTapAction.Builder a = com.wizards.mtga.gre.AutoTapAction.newBuilder().setInstanceId(iid(c));
        int abilityGrp = abilities.abilityId(ma, grp(c));
        if (abilityGrp != 0) a.setAbilityGrpId(abilityGrp);
        a.getManaPaymentOptionBuilder().addMana(com.wizards.mtga.gre.ManaInfo.newBuilder().setColor(color).setSrcInstanceId(iid(c)).setAbilityGrpId(abilityGrp));
        return a.build();
    }

    /** False if the ability (or a sub-ability) needs a target and none is legal: casting it could only fail. */
    private static boolean hasLegalTargets(SpellAbility sa) {
        try {
            for (SpellAbility s = sa; s != null; s = s.getSubAbility()) {
                forge.game.spellability.TargetRestrictions t = s.getTargetRestrictions();
                if (t != null && t.getMinTargets(s.getHostCard(), s) > 0 && !t.hasCandidates(s)) return false;
            }
        } catch (RuntimeException e) {
            return true;      // unusual targeting: let Forge decide when it is cast
        }
        return true;
    }

    private static boolean affordable(SpellAbility sa, Player me) {
        try {
            sa.setActivatingPlayer(me);
            return forge.ai.ComputerUtilMana.canPayManaCost(sa, me, 0, false);
        } catch (RuntimeException e) {
            return true;
        }
    }

    private static final String[] MANA_CODES = {"W", "U", "B", "R", "G"};
    private static final com.wizards.mtga.gre.ManaColor[] MANA_COLORS = {com.wizards.mtga.gre.ManaColor.ManaColor_White,
            com.wizards.mtga.gre.ManaColor.ManaColor_Blue, com.wizards.mtga.gre.ManaColor.ManaColor_Black,
            com.wizards.mtga.gre.ManaColor.ManaColor_Red, com.wizards.mtga.gre.ManaColor.ManaColor_Green};

    /**
     * A mana ability that makes one of several colors ("Add {U} or {R}", Forge Produced$ Combo / Any): one
     * ManaPaymentOption per color, each mana marked Predictive, so the client shows its color wheel when tapped by hand
     * (ActionsAvailableWorkflowUtils_ColorPicker). The reply carries only the chosen option.
     */
    private static void addManaOptions(Action.Builder ab, SpellAbility ma, int iid, int abilityGrp) {
        forge.game.spellability.AbilityManaPart mp = ma.getManaPart();
        if (mp == null) return;
        List<Integer> colors = new ArrayList<>();
        for (int i = 0; i < MANA_CODES.length; i++) if (mp.canProduce(MANA_CODES[i], ma)) colors.add(i);
        if (colors.size() < 2) return;
        int idx = 0;
        for (int i : colors) {
            ab.addManaPaymentOptions(com.wizards.mtga.gre.ManaPaymentOption.newBuilder().setOptionIndex(idx++)
                    .addMana(com.wizards.mtga.gre.ManaInfo.newBuilder().setColor(MANA_COLORS[i]).setSrcInstanceId(iid)
                            .setAbilityGrpId(abilityGrp).setCount(1)
                            .addSpecs(com.wizards.mtga.gre.ManaInfo.Spec.newBuilder().setType(com.wizards.mtga.gre.ManaSpecType.ManaSpecType_Predictive))));
        }
    }

    /** The color the player picked on the client's wheel becomes Forge's express choice for this activation. */
    private static void applyManaChoice(SpellAbility ma, Action a) {
        forge.game.spellability.AbilityManaPart mp = ma.getManaPart();
        if (mp == null) return;
        mp.clearExpressChoice();
        if (a.getManaPaymentOptionsCount() != 1 || a.getManaPaymentOptions(0).getManaCount() == 0) return;
        com.wizards.mtga.gre.ManaColor chosen = a.getManaPaymentOptions(0).getMana(0).getColor();
        for (int i = 0; i < MANA_COLORS.length; i++) {
            if (MANA_COLORS[i] == chosen && mp.canProduce(MANA_CODES[i], ma)) {
                mp.setExpressChoice(MANA_CODES[i]);
                System.out.println("[mtga] mana color chosen: " + MANA_CODES[i] + " from " + ma.getHostCard());
            }
        }
    }

    private static void addManaCost(Action.Builder ab, SpellAbility sa, int objectId) {
        if (sa.getPayCosts() == null) return;
        ab.addAllManaCost(manaRequirements(sa.getPayCosts().getTotalMana(), objectId));
    }

    // ---- the human's Forge controller --------------------------------------------------------------------------

    /** The human seat: AI machinery for everything not yet routed to the client, client input for the key decisions. */
    private final class MtgaLobbyPlayer extends LobbyPlayerAi {
        MtgaLobbyPlayer(String name) { super(name, null); }

        @Override
        public Player createIngamePlayer(Game g, int id) {
            Player p = new Player(getName(), g, id);
            p.setFirstController(new MtgaController(g, p, this));
            return p;
        }
    }

    /**
     * Cost choices for the human (ninjutsu "return an unblocked attacker", "sacrifice a creature", "discard a card",
     * "exile a card from your graveyard"...): Forge pays costs through ComputerUtil -> controller.getCostDecisionMaker,
     * which for the AI picks by itself. When there is a real choice, ask the client (SelectNReq, NonMana_Payment);
     * everything else (from-source costs, mana, tapping, counters, random/whole-hand discards) stays with the AI logic.
     */
    private final class MtgaCostDecision extends forge.ai.AiCostDecision {
        MtgaCostDecision(Player p, SpellAbility sa, boolean effect) { super(p, sa, effect); }

        private forge.game.cost.PaymentDecision ask(List<Card> valid, int amount, int promptId) {
            if (amount <= 0 || valid.size() <= amount) return null;      // no choice to make: let the AI path pay
            CardCollection chosen = askCostSelection(valid, amount, promptId, source);
            return chosen.size() == amount ? forge.game.cost.PaymentDecision.card(chosen) : null;
        }

        private List<Card> valid(Iterable<Card> from, String type) {
            return new ArrayList<>(forge.game.card.CardLists.getValidCards(new CardCollection(from), type.split(";"), player, source, ability));
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostReturn cost) {
            if (!cost.payCostFromSource()) {
                List<Card> v = valid(player.getCardsIn(ZoneType.Battlefield), cost.getType());
                int n = cost.getAbilityAmount(ability);
                if (v.size() > n) {
                    boolean creatures = v.stream().allMatch(Card::isCreature);
                    forge.game.cost.PaymentDecision d = ask(v, n, creatures && n == 1 ? PROMPT_RETURN_CREATURE : 0);
                    if (d != null) return d;
                }
            }
            return super.visit(cost);
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostSacrifice cost) {
            if (!cost.payCostFromSource() && !cost.getType().equals("OriginalHost") && !cost.getAmount().equals("All")) {
                List<Card> v = valid(player.getCardsIn(ZoneType.Battlefield), cost.getType());
                v.removeIf(c -> !c.canBeSacrificedBy(ability, isEffect()));
                int n = cost.getAbilityAmount(ability);
                if (v.size() > n) {
                    forge.game.cost.PaymentDecision d = ask(v, n, n == 1 ? PROMPT_SACRIFICE : 0);
                    if (d != null) return d;
                }
            }
            return super.visit(cost);
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostDiscard cost) {
            String type = cost.getType();
            if (!cost.payCostFromSource() && !type.equals("Hand") && !type.equals("Random") && !type.equals("LastDrawn")
                    && !type.contains("WithSameName") && !type.contains("WithDifferentNames")) {
                List<Card> v = valid(player.getCardsIn(ZoneType.Hand), type);
                v.remove(source);
                int n = cost.getAbilityAmount(ability);
                if (v.size() > n) {
                    forge.game.cost.PaymentDecision d = ask(v, n, 0);
                    if (d != null) return d;
                }
            }
            return super.visit(cost);
        }

        @Override
        public forge.game.cost.PaymentDecision visit(forge.game.cost.CostExile cost) {
            if (!cost.payCostFromSource() && cost.getFrom().size() == 1 && !cost.getType().equals("All") && !cost.getAmount().equals("All")) {
                List<Card> v = valid(player.getCardsIn(cost.getFrom().get(0)), cost.getType());
                v.remove(source);
                int n = cost.getAbilityAmount(ability);
                if (v.size() > n) {
                    forge.game.cost.PaymentDecision d = ask(v, n, 0);
                    if (d != null) return d;
                }
            }
            return super.visit(cost);
        }
    }

    private final class MtgaController extends PlayerControllerAi {
        MtgaController(Game g, Player p, LobbyPlayer lp) { super(g, p, lp); }

        @Override public boolean isAI() { return false; }

        private boolean live() { return introSent && session != null; }

        @Override
        public forge.game.cost.CostDecisionMakerBase getCostDecisionMaker(Player p, SpellAbility ability, boolean effect, String prompt) {
            return live() && p == human ? new MtgaCostDecision(p, ability, effect) : super.getCostDecisionMaker(p, ability, effect, prompt);
        }

        @Override
        public CardCollectionView chooseCardsToDiscardToMaximumHandSize(int numDiscard) {
            List<Card> hand = new ArrayList<>(player.getCardsIn(ZoneType.Hand));
            CardCollection chosen = askSelectCards(hand, numDiscard, numDiscard,
                    com.wizards.mtga.gre.SelectionContext.SelectionContext_Discard, PROMPT_DISCARD, "count", null);
            return chosen.size() == numDiscard ? chosen : super.chooseCardsToDiscardToMaximumHandSize(numDiscard);
        }

        @Override
        public CardCollectionView tuckCardsViaMulligan(CardCollectionView hand, int cardsToReturn) {
            return new CardCollection();       // deferred: the client picks the bottom cards when it keeps (askMulligan)
        }

        @Override
        public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
            return askMulligan(cardsToReturn);
        }

        @Override
        public Player chooseStartingPlayer(boolean isFirstGame) {
            if ("block".equals(System.getProperty("mtga.rig"))) return opponent;      // test rig: let the AI attack first
            if ("playtest".equals(System.getProperty("mtga.deck"))) return player;      // solo sandbox: you start
            return super.chooseStartingPlayer(isFirstGame);
        }

        @Override
        public boolean payManaCost(forge.card.mana.ManaCost toPay, forge.game.cost.CostPartMana costPartMana, SpellAbility sa,
                                   String prompt, forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
            if (!settings.manualMana()) return super.payManaCost(toPay, costPartMana, sa, prompt, matrix, effect);
            return forge.game.player.PlaySpellAbility.payManaCost(this, toPay, costPartMana, sa, player, prompt, matrix, effect);
        }

        @Override
        public boolean applyManaToCost(forge.game.mana.ManaCostBeingPaid toPay, SpellAbility ability, String prompt,
                                       forge.game.mana.ManaConversionMatrix matrix, boolean effect) {
            if (!settings.manualMana()) return super.applyManaToCost(toPay, ability, prompt, matrix, effect);
            return askManaPayment(toPay, ability, player, this);
        }

        @Override
        public boolean playChosenSpellAbility(SpellAbility sa) {
            if (sa.isLandAbility()) return super.playChosenSpellAbility(sa);
            // tap what the client previewed (MTGA pays with the shown solution); the mana floats and Forge's payment uses it first
            List<SpellAbility> taps = pendingTaps;
            Map<SpellAbility, String> tapColors = pendingTapColors == null ? Map.of() : pendingTapColors;
            pendingTaps = null;
            pendingTapColors = null;
            if (taps != null && !settings.manualMana()) {
                for (SpellAbility ma : taps) {
                    if (ma.getHostCard().isTapped() || !ma.canPlay()) continue;
                    // a dual land in the preview must make the color the preview assigned (Spirebluff Canal made R
                    // for a {U} spell: the payment failed and the spell stayed stuck on the stack)
                    if (ma.getManaPart() != null) {
                        ma.getManaPart().clearExpressChoice();
                        String color = tapColors.get(ma);
                        if (color != null) ma.getManaPart().setExpressChoice(color);
                    }
                    forge.game.player.PlaySpellAbility.playSpellAbility(this, player, ma);
                }
            }
            if (sa.isSpell() && live()) {
                List<forge.game.spellability.OptionalCostValue> opts = forge.game.GameActionUtil.getOptionalCostValues(sa);
                if (!opts.isEmpty()) {
                    List<forge.game.spellability.OptionalCostValue> chosen = askOptionalCosts(sa, opts);
                    if (!chosen.isEmpty()) sa = forge.game.GameActionUtil.addOptionalCosts(sa, chosen);
                }
            }
            Card castHost = sa.getHostCard();
            ZoneType castFrom = sa.isSpell() && castHost.getZone() != null ? castHost.getZone().getZoneType() : null;
            boolean played = forge.ai.ComputerUtil.handlePlayingSpellAbility(player, sa, root -> {
                if (root.costHasManaX() && root.getXManaCostPaid() == null) {
                    int max = Math.max(0, forge.ai.ComputerUtilMana.determineLeftoverMana(root, player, false));
                    root.setXManaCostPaid(askNumber(root.getHostCard(), 0, max));
                }
                for (SpellAbility cur = root; cur != null; cur = cur.getSubAbility()) {
                    if (cur.usesTargeting()) {
                        cur.clearTargets();
                        askTargets(cur);
                    }
                }
            });
            // Forge's failure path (ComputerUtil "AI failed to play") leaves a spell cast from hand lying in the stack
            // zone without a stack item, which froze the game: put it back where it was cast from.
            if (!played && castFrom != null && castFrom != ZoneType.Stack) {
                Card now = game.getCardState(castHost);
                if (now != null && now.isInZone(ZoneType.Stack) && game.getStack().getInstanceMatchingSpellAbilityID(sa) == null) {
                    System.out.println("[mtga] cast of " + now + " was not completed (cancelled or not paid): returning it to " + castFrom);
                    game.getAction().moveTo(castFrom, now, null, null);
                }
            }
            return true;
        }

        @Override
        public void orderAndPlaySimultaneousSa(List<SpellAbility> activePlayerSAs) {
            for (SpellAbility sa : activePlayerSAs) {
                boolean targeted = false;
                for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) targeted |= cur.usesTargeting();
                if (!live() || !sa.isTrigger() || sa.isCopied() || !targeted || sa.getApi() == forge.game.ability.ApiType.Charm) {
                    super.orderAndPlaySimultaneousSa(List.of(sa));
                    continue;
                }
                boolean ok = true;
                for (SpellAbility cur = sa; cur != null; cur = cur.getSubAbility()) {
                    if (!cur.usesTargeting()) continue;
                    cur.clearTargets();
                    ok &= askTargets(cur);
                }
                if (ok && sa.isTargetNumberValid()) forge.ai.ComputerUtil.playStack(sa, player, game);
                else super.orderAndPlaySimultaneousSa(List.of(sa));
            }
        }

        @Override
        public boolean chooseTargetsFor(SpellAbility currentAbility) {
            return askTargets(currentAbility);
        }

        @Override
        public List<SpellAbility> chooseSpellAbilityToPlay() {
            return askPriority(player);
        }

        @Override public void declareAttackers(Player attacker, forge.game.combat.Combat combat) { askAttackers(attacker, combat); }

        @Override public void declareBlockers(Player defender, forge.game.combat.Combat combat) { askBlockers(defender, combat); }

        // -- optional choices / replacement effects --

        @Override
        public boolean payCostToPreventEffect(forge.game.cost.Cost cost, SpellAbility sa, boolean alreadyPaid, forge.util.collect.FCollectionView<Player> allPayers) {
            if (!live() || !forge.ai.ComputerUtilCost.canPayCost(cost, sa, player, true)) return live() ? false : super.payCostToPreventEffect(cost, sa, alreadyPaid, allPayers);
            int life = 0;
            boolean onlyLife = true;
            for (forge.game.cost.CostPart part : cost.getCostParts()) {
                if (part instanceof forge.game.cost.CostPayLife pl) life += pl.getAbilityAmount(sa);
                else onlyLife = false;
            }
            int prompt = 0;
            List<PromptParameter> params = new ArrayList<>();
            if (onlyLife && life == 2) prompt = PROMPT_PAY_2_LIFE;
            else if (onlyLife && life > 0) {
                prompt = PROMPT_PAY_N_LIFE;
                params.add(numberParam("CardId", iid(sa.getHostCard())));
                params.add(numberParam("Number", life));
            } else prompt = db.promptId("Pay " + cost.toSimpleString() + "?");
            Card host = sa.getHostCard();
            int reAbility = 0;
            for (forge.game.replacement.ReplacementEffect re : host.getReplacementEffects()) {
                reAbility = abilities.abilityByText(grp(host), re.getDescription(), host);
                if (reAbility != 0) break;
            }
            if (!askOptional(host, prompt, params, "pay " + cost.toSimpleString(), reAbility)) return false;
            final forge.game.cost.CostPayment pay = new forge.game.cost.CostPayment(cost, sa);
            return pay.payComputerCosts(new forge.ai.AiCostDecision(player, sa, true));
        }

        @Override
        public boolean confirmAction(SpellAbility sa, forge.game.player.PlayerActionConfirmMode mode, String message, List<String> options, Card cardToShow, Map<String, Object> params) {
            if (!live()) return super.confirmAction(sa, mode, message, options, cardToShow, params);
            if (sa != null && sa.getApi() == forge.game.ability.ApiType.Explore && params != null && params.get("RevealedCard") instanceof Card revealed) {
                return askExplore(sa.getHostCard(), revealed);
            }
            Card src = cardToShow != null ? cardToShow : sa == null ? null : sa.getHostCard();
            return askOptional(src, promptFor(message), List.of(), message);
        }

        /** "You may reveal this card from your opening hand" (Devourer of Destiny, Leylines, Gemstone Caverns): ask per card. */
        @Override
        public List<SpellAbility> chooseSaToActivateFromOpeningHand(List<SpellAbility> usable) {
            if (!live()) return super.chooseSaToActivateFromOpeningHand(usable);
            List<SpellAbility> chosen = new ArrayList<>();
            for (SpellAbility sa : usable) {
                String q = sa.getDescription();
                if (q == null || q.isEmpty()) q = sa.getHostCard().getName() + ": use from your opening hand?";
                if (askOptional(sa.getHostCard(), promptFor(q), List.of(), "opening hand " + sa.getHostCard())) chosen.add(sa);
            }
            return chosen;
        }

        @Override
        public boolean confirmReplacementEffect(forge.game.replacement.ReplacementEffect re, SpellAbility effectSA, GameEntity affected, String question) {
            if (!live()) return super.confirmReplacementEffect(re, effectSA, affected, question);
            return askOptional(re.getHostCard(), promptFor(question), List.of(), question);
        }

        @Override
        public boolean confirmTrigger(forge.game.trigger.WrappedAbility wrapper) {
            if (!live() || wrapper.isMandatory()) return super.confirmTrigger(wrapper);
            SpellAbility sa = wrapper.getWrappedAbility();
            String q = sa.getStackDescription();
            return askOptional(sa.getHostCard(), promptFor(q), List.of(), "trigger " + q);
        }

        @Override
        public List<AbilitySub> chooseModeForAbility(SpellAbility sa, List<AbilitySub> possible, int min, int num, boolean allowRepeat) {
            if (!live() || allowRepeat) return super.chooseModeForAbility(sa, possible, min, num, allowRepeat);
            List<AbilitySub> chosen = askModes(sa, possible, min, num);
            return chosen == null ? super.chooseModeForAbility(sa, possible, min, num, allowRepeat) : chosen;
        }

        @Override
        public byte chooseColor(String message, SpellAbility sa, forge.card.ColorSet colors) {
            if (!live() || colors.countColors() < 2) return super.chooseColor(message, sa, colors);
            List<Byte> c = askColors(sa == null ? null : sa.getHostCard(), colors, false, 1, 1);
            return c.isEmpty() ? super.chooseColor(message, sa, colors) : c.get(0);
        }

        @Override
        public byte chooseColorAllowColorless(String message, Card card, forge.card.ColorSet colors) {
            if (!live()) return super.chooseColorAllowColorless(message, card, colors);
            List<Byte> c = askColors(card, colors, true, 1, 1);
            return c.isEmpty() ? super.chooseColorAllowColorless(message, card, colors) : c.get(0);
        }

        @Override
        public forge.card.ColorSet chooseColors(String message, SpellAbility sa, int min, int max, forge.card.ColorSet options) {
            if (!live() || options.countColors() <= min) return super.chooseColors(message, sa, min, max, options);
            List<Byte> c = askColors(sa == null ? null : sa.getHostCard(), options, false, min, Math.min(max, options.countColors()));
            if (c.size() < min) return super.chooseColors(message, sa, min, max, options);
            int mask = 0;
            for (byte b : c) mask |= b;
            return forge.card.ColorSet.fromMask(mask);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForSurveil(CardCollection topN) {
            if (!live() || topN.isEmpty()) return super.arrangeForSurveil(topN);
            return askSurveil(topN);
        }

        @Override
        public void reveal(CardCollectionView cards, ZoneType zone, Player owner, String messagePrefix, boolean addSuffix) {
            super.reveal(cards, zone, owner, messagePrefix, addSuffix);
            if (!live() || cards.isEmpty()) return;
            List<Card> hidden = new ArrayList<>();
            for (Card c : cards) if (c.isInZone(ZoneType.Library) || (c.isInZone(ZoneType.Hand) && c.getOwner() != human)) hidden.add(c);
            if (!hidden.isEmpty() && player == human) askLookAt(hidden, null);
        }

        @Override
        public org.apache.commons.lang3.tuple.ImmutablePair<CardCollection, CardCollection> arrangeForScry(CardCollection topN) {
            if (!live() || topN.isEmpty()) return super.arrangeForScry(topN);
            return askScry(topN);
        }

        @Override
        public CardCollectionView orderMoveToZoneList(CardCollectionView cards, ZoneType destinationZone, SpellAbility source) {
            if (!live() || destinationZone != ZoneType.Library || cards.size() < 2) return super.orderMoveToZoneList(cards, destinationZone, source);
            return askOrder(cards, true);
        }

        @Override
        public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
            if (!live()) return super.announceRequirements(ability, min, max, announce);
            return askNumber(ability.getHostCard(), min, max);
        }

        @Override
        public int chooseNumber(SpellAbility sa, String title, int min, int max) {
            if (!live()) return super.chooseNumber(sa, title, min, max);
            return askNumber(sa == null ? null : sa.getHostCard(), min, max);
        }

        @Override
        public List<Card> chooseCardsForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList, int min, int max,
                                                   forge.game.player.DelayedReveal delayedReveal, String selectPrompt, Player decider) {
            if (!live() || fetchList.isEmpty()) return super.chooseCardsForZoneChange(destination, origin, sa, fetchList, min, max, delayedReveal, selectPrompt, decider);
            int m = Math.min(max, fetchList.size());
            return askSelectCards(new ArrayList<>(fetchList), Math.min(min, m), m, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution,
                    zoneChangePrompt(selectPrompt, destination, origin, m), null, sa == null ? null : sa.getHostCard());
        }

        @Override
        public Card chooseSingleCardForZoneChange(ZoneType destination, List<ZoneType> origin, SpellAbility sa, CardCollection fetchList,
                                                  forge.game.player.DelayedReveal delayedReveal, String selectPrompt, boolean isOptional, Player decider) {
            if (!live() || fetchList.isEmpty()) return super.chooseSingleCardForZoneChange(destination, origin, sa, fetchList, delayedReveal, selectPrompt, isOptional, decider);
            CardCollection chosen = askSelectCards(new ArrayList<>(fetchList), isOptional ? 0 : 1, 1, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution,
                    zoneChangePrompt(selectPrompt, destination, origin, 1), null, sa == null ? null : sa.getHostCard());
            if (chosen.isEmpty()) return isOptional ? null : fetchList.get(0);
            return chosen.get(0);
        }

        @Override
        public CardCollectionView choosePermanentsToSacrifice(SpellAbility sa, int min, int max, CardCollectionView valid, String message) {
            if (!live() || valid.isEmpty()) return super.choosePermanentsToSacrifice(sa, min, max, valid, message);
            int m = Math.min(max, valid.size());
            int prompt = promptFor(message);
            CardCollection chosen = askSelectCards(new ArrayList<>(valid), Math.min(min, m), m, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution,
                    prompt != 0 ? prompt : m == 1 ? PROMPT_SACRIFICE : 0, null, sa == null ? null : sa.getHostCard());
            return chosen.size() >= Math.min(min, m) ? chosen : super.choosePermanentsToSacrifice(sa, min, max, valid, message);
        }

        @Override
        public CardCollectionView choosePermanentsToDestroy(SpellAbility sa, int min, int max, CardCollectionView valid, String message) {
            if (!live() || valid.isEmpty()) return super.choosePermanentsToDestroy(sa, min, max, valid, message);
            int m = Math.min(max, valid.size());
            CardCollection chosen = askSelectCards(new ArrayList<>(valid), Math.min(min, m), m, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution,
                    promptFor(message), null, sa == null ? null : sa.getHostCard());
            return chosen.size() >= Math.min(min, m) ? chosen : super.choosePermanentsToDestroy(sa, min, max, valid, message);
        }

        @Override
        public CardCollection chooseCardsToDiscardFrom(Player playerDiscard, SpellAbility sa, CardCollection valid, int min, int max, CardCollectionView visibleToChooser) {
            if (!live() || valid.isEmpty() || playerDiscard != player) return super.chooseCardsToDiscardFrom(playerDiscard, sa, valid, min, max, visibleToChooser);
            int m = Math.min(max, valid.size());
            CardCollection chosen = askSelectCards(new ArrayList<>(valid), Math.min(min, m), m, com.wizards.mtga.gre.SelectionContext.SelectionContext_Discard,
                    PROMPT_DISCARD, "count", sa == null ? null : sa.getHostCard());
            return chosen.size() >= Math.min(min, m) ? chosen : super.chooseCardsToDiscardFrom(playerDiscard, sa, valid, min, max, visibleToChooser);
        }

        @Override
        public CardCollectionView chooseCardsForEffect(CardCollectionView sourceList, SpellAbility sa, String title, int min, int max, boolean isOptional, Map<String, Object> params) {
            if (!live() || sourceList.isEmpty()) return super.chooseCardsForEffect(sourceList, sa, title, min, max, isOptional, params);
            int m = Math.min(max, sourceList.size());
            return askSelectCards(new ArrayList<>(sourceList), isOptional ? 0 : Math.min(min, m), m,
                    com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution, promptFor(title), null, sa == null ? null : sa.getHostCard());
        }

        @Override
        public <T extends GameEntity> T chooseSingleEntityForEffect(forge.util.collect.FCollectionView<T> optionList, forge.game.player.DelayedReveal delayedReveal,
                                                                    SpellAbility sa, String title, boolean isOptional, Player relatedPlayer, Map<String, Object> params) {
            if (!live() || optionList.isEmpty()) return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
            // a forced choice of one (Equip resolving onto its only target) is not a question on Arena
            if (!isOptional && delayedReveal == null && optionList.size() == 1) return optionList.get(0);
            List<Card> cards = new ArrayList<>();
            for (T t : optionList) {
                if (!(t instanceof Card c)) return super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
                cards.add(c);
            }
            int prompt = promptFor(title);
            CardCollection chosen = askSelectCards(cards, isOptional ? 0 : 1, 1, com.wizards.mtga.gre.SelectionContext.SelectionContext_Resolution,
                    prompt == 0 ? PROMPT_CHOOSE_CARD : prompt, null, sa == null ? null : sa.getHostCard());
            if (chosen.isEmpty()) return isOptional ? null : super.chooseSingleEntityForEffect(optionList, delayedReveal, sa, title, isOptional, relatedPlayer, params);
            @SuppressWarnings("unchecked") T t = (T) chosen.get(0);
            return t;
        }
    }
}

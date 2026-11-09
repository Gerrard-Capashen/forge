package forge.mtga.forge;

import com.wizards.mtga.gre.AnnotationInfo;
import com.wizards.mtga.gre.AnnotationType;
import com.wizards.mtga.gre.CardColor;
import com.wizards.mtga.gre.CardType;
import com.wizards.mtga.gre.ControllerType;
import com.wizards.mtga.gre.GameObjectInfo;
import com.wizards.mtga.gre.GameObjectType;
import com.wizards.mtga.gre.GameStage;
import com.wizards.mtga.gre.GameStateMessage;
import com.wizards.mtga.gre.GameStateType;
import com.wizards.mtga.gre.Int32Value;
import com.wizards.mtga.gre.KeyValuePairInfo;
import com.wizards.mtga.gre.KeyValuePairValueType;
import com.wizards.mtga.gre.Phase;
import com.wizards.mtga.gre.PlayerInfo;
import com.wizards.mtga.gre.PlayerStatus;
import com.wizards.mtga.gre.Step;
import com.wizards.mtga.gre.SubType;
import com.wizards.mtga.gre.SuperType;
import com.wizards.mtga.gre.TeamInfo;
import com.wizards.mtga.gre.TeamStatus;
import com.wizards.mtga.gre.TurnInfo;
import com.wizards.mtga.gre.UInt32Value;
import com.wizards.mtga.gre.Visibility;
import com.wizards.mtga.gre.ZoneInfo;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollectionView;
import forge.game.card.CardView;
import forge.card.CardStateName;
import forge.game.event.GameEvent;
import forge.game.event.GameEventCardDamaged;
import forge.game.event.GameEventCardSacrificed;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellRemovedFromStack;
import forge.game.event.GameEventSpellResolved;
import forge.game.phase.PhaseType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * Turns the live Forge game into GRE game-state messages for one client seat: a Full state on connect, then Diffs
 * computed against what was last sent, with the annotations the client needs to animate them.
 *
 * <p>What the client derives by itself from a diff is limited (see GreInterface.GetDiffChanges in the decompiled client):
 * zone membership, P/T, damage, attack/block state, summoning sickness. Everything else only animates when an
 * annotation says so: turn change (NewTurnStarted), phase ladder (PhaseOrStepModified), tap/untap
 * (TappedUntappedPermanent), life (ModifiedLife), counters (CounterAdded/Removed + persistent Counter), zone moves
 * (ObjectIdChanged immediately followed by ZoneTransfer for the new id), stack abilities (AbilityInstanceCreated/Deleted),
 * tokens (TokenCreated/Deleted) and resolution (ResolutionStart/Complete).
 *
 * <p>Instance ids follow the GRE convention: an object gets a new instance id whenever it changes zone. A card's first id
 * is its Forge id + 100 (keeps test rigs deterministic); later ids come from a counter starting at 1000.
 * Must be called on the Forge game thread.
 */
public final class StateSync {
    public static final int HUMAN_SEAT = 1, AI_SEAT = 2;
    static final int Z_REVEALED = 25, Z_COMMAND = 26, Z_STACK = 27, Z_BF = 28, Z_EXILE = 29, Z_LIMBO = 30,
            Z_REVEALED_OPP = 24, Z_PHASED_OUT = 23, Z_SIDEBOARD = 34, Z_SIDEBOARD_OPP = 44;

    private final Game game;
    private final Player human, ai;
    private final CardDb db;
    private final Map<String, Integer> grpByName;
    private final String matchId;
    private ToIntFunction<SpellAbility> abilityGrp = sa -> 0;

    private int gsId;
    private int annId;
    private Map<Integer, GameObjectInfo> sentObjs = new HashMap<>();
    private Map<Integer, Integer> sentDoors = new HashMap<>();
    private Map<Integer, ZoneInfo> sentZones = new HashMap<>();
    private TurnInfo sentTurn;
    private final Map<Integer, PlayerInfo> sentPlayers = new HashMap<>();
    private final Map<String, AnnotationInfo> sentPersistent = new LinkedHashMap<>();
    private List<com.wizards.mtga.gre.ResultSpec> pendingResults;
    private com.wizards.mtga.gre.MatchState pendingMatchState = com.wizards.mtga.gre.MatchState.MatchState_MatchComplete;
    /** The GameInfo block of this game (game number and win condition of a best-of-three match). */
    private GameInfoSupplier info = DEFAULT_INFO;

    // ---- instance ids: key "c<forge card id>" (cards) or "s<stack instance id>" (abilities on the stack)
    private final Map<String, Integer> iidByKey = new HashMap<>();
    private final Map<String, Integer> zoneByKey = new HashMap<>();
    private final Map<Integer, Integer> forgeByIid = new HashMap<>();
    private final Map<Integer, Integer> stackByIid = new HashMap<>();
    private final Set<Integer> usedIids = new HashSet<>();
    private int nextIid = 1000;

    // ---- hints from Forge events, consumed by the next diff
    private final List<AnnotationInfo> earlyAnns = new ArrayList<>();
    /** Forge ids of the permanents damaged since the last diff: their death is SBA_Damage / SBA_ZeroLoyalty, which the client folds into the combat frame. */
    private final Set<Integer> damagedCards = new HashSet<>();
    private final List<AnnotationInfo> lateAnns = new ArrayList<>();
    private final Map<Integer, String> categoryHint = new HashMap<>();
    private final Map<Integer, List<AnnotationInfo>> playerDamage = new HashMap<>();
    private final Map<Integer, Integer> lifeAffector = new HashMap<>();
    private int resolutionStartedFor = -1;
    /** Casts/activations to report as UserActionTaken once their stack object has an id (stack id, card id or -1, seat, type, ability). */
    private record PendingAction(int stackId, int cardId, int seat, com.wizards.mtga.gre.ActionType type, int abilityGrp) { }
    private final List<PendingAction> pendingActions = new ArrayList<>();
    private Player decisionOverride;
    private final int[] mulligans = new int[3];
    /** Seats whose mulligan question is outstanding: the client shows "opponent is deciding" from PlayerInfo.pendingMessageType. */
    private final boolean[] mulliganPending = new boolean[3];
    private final boolean[] startPending = new boolean[3];
    private final Set<Integer> revealed = new HashSet<>();
    private final Map<String, AnnotationInfo.Builder> extraPersistent = new LinkedHashMap<>();
    private List<com.wizards.mtga.gre.Action> hypothetical = List.of();
    private List<com.wizards.mtga.gre.ActionInfo> sentActions = List.of();

    /** A persistent annotation kept until removed (e.g. ReplacementEffect while asking a shockland's question). */
    public void putPersistent(String key, AnnotationInfo.Builder a) { extraPersistent.put(key, a); }

    public void removePersistent(String key) { extraPersistent.remove(key); }

    /**
     * Actions the human could take, shown in GameStateMessage.actions. The client moves graveyard/exile cards that have one
     * next to the hand (adventures, flashback...). Every diff replaces the client's list, so it is re-sent each time.
     */
    public void setHypotheticalActions(List<com.wizards.mtga.gre.Action> actions) { hypothetical = actions; }

    private List<com.wizards.mtga.gre.ActionInfo> actionInfos(Snapshot s) {
        List<com.wizards.mtga.gre.ActionInfo> out = new ArrayList<>();
        int n = 0;
        for (com.wizards.mtga.gre.Action a : hypothetical) {
            if (!s.objs.containsKey(a.getInstanceId())) continue;
            out.add(com.wizards.mtga.gre.ActionInfo.newBuilder().setActionId(++n).setSeatId(HUMAN_SEAT).setAction(a).build());
        }
        return out;
    }

    /** Show these (hidden) cards to the human until {@link #clearRevealed()} (searches, scry, London mulligan). */
    public void reveal(Iterable<Card> cards) { for (Card c : cards) revealed.add(c.getId()); }

    public void clearRevealed() { revealed.clear(); }

    /** The next diff reports the game/match as finished with these results. */
    public void setResults(List<com.wizards.mtga.gre.ResultSpec> results) { setResults(results, com.wizards.mtga.gre.MatchState.MatchState_MatchComplete); }

    /** Same, with the match state the client moves to: GameComplete when the match goes on with another game. */
    public void setResults(List<com.wizards.mtga.gre.ResultSpec> results, com.wizards.mtga.gre.MatchState state) {
        pendingResults = results;
        pendingMatchState = state;
    }

    /** GameInfo of this game's messages (game number, win condition); the default is game 1 of a single-game match. */
    public void setInfo(GameInfoSupplier i) { info = i; }

    public GameInfoSupplier info() { return info; }

    /** A later game of the same match: the client keeps counting game state, annotation and instance ids across games. */
    public void continueFrom(StateSync prev) {
        gsId = prev.gsId;
        annId = prev.annId;
        nextIid = prev.nextIid;
    }

    /**
     * A GameStateMessage that carries only a complete GameInfo with {@code state} (+ the per-game {@code results}): between
     * games, to move the client to Sideboarding. Counts as the last message sent.
     */
    public GameStateMessage.Builder matchStateDiff(com.wizards.mtga.gre.MatchState state, List<com.wizards.mtga.gre.ResultSpec> results) {
        GameStateMessage.Builder m = GameStateMessage.newBuilder().setType(GameStateType.GameStateType_Diff);
        info.fill(m.getGameInfoBuilder(), matchId);
        m.getGameInfoBuilder().setStage(GameStage.GameStage_GameOver).setMatchState(state).addAllResults(results);
        m.setGameStateId(++gsId).setPrevGameStateId(gsId - 1);
        return m;
    }

    public StateSync(Game game, Player human, Player ai, CardDb db, Map<String, Integer> grpByName, String matchId) {
        this.game = game;
        this.human = human;
        this.ai = ai;
        this.db = db;
        this.grpByName = grpByName;
        this.matchId = matchId;
    }

    public void setAbilityResolver(ToIntFunction<SpellAbility> f) { abilityGrp = f; }

    /** Seat that must currently decide (shown by the client as the deciding player); null = priority player. */
    public void setDecisionPlayer(Player p) { decisionOverride = p; }

    public void setMulliganCount(Player p, int n) { mulligans[seatOf(p)] = n; }

    public void setMulliganPending(Player p, boolean on) { mulliganPending[seatOf(p)] = on; }

    /** The seat is choosing who plays first (the loser of the last game): the other client shows "waiting for opponent". */
    public void setStartPending(Player p, boolean on) { startPending[seatOf(p)] = on; }

    public int lastStateId() { return gsId; }

    public static int seat(Player p, Player human) { return p == human ? HUMAN_SEAT : AI_SEAT; }

    private int seatOf(Player p) { return seat(p, human); }

    public Player player(int seat) { return seat == HUMAN_SEAT ? human : seat == AI_SEAT ? ai : null; }

    private static int zoneId(ZoneType t, int seat) {
        switch (t) {
            case Library: return seat == HUMAN_SEAT ? 31 : 41;
            case Hand: return seat == HUMAN_SEAT ? 32 : 42;
            case Graveyard: return seat == HUMAN_SEAT ? 33 : 43;
            case Battlefield: return Z_BF;
            case Stack: return Z_STACK;
            case Exile: return Z_EXILE;
            case Command: return Z_COMMAND;
            default: return Z_LIMBO;
        }
    }

    // ---- instance ids ------------------------------------------------------------------------------------------

    /** Current instance id of a card (assigned on first use). */
    public int iid(Card c) {
        Integer i = iidByKey.get("c" + c.getId());
        if (i != null) return i;
        int id = c.getId() + 100;
        if (id >= 1000 || usedIids.contains(id)) id = fresh();
        bind("c" + c.getId(), id, -1);
        forgeByIid.put(id, c.getId());
        return id;
    }

    /** Has the client been sent this card in a game state (it has a card view for it)? */
    public boolean inClientState(Card c) {
        return c != null && zoneByKey.getOrDefault("c" + c.getId(), -1) != -1;      // -1: only an id handed out for a prompt
    }

    /** The Forge card behind an instance id (current or older id of the same card), or null. */
    public Card card(int iid) {
        Integer f = forgeByIid.get(iid);
        return f == null ? null : game.findById(f);
    }

    /** The stack item behind an ability instance id, or null. */
    public SpellAbilityStackInstance stackItem(int iid) {
        Integer sid = stackByIid.get(iid);
        if (sid == null) return null;
        for (SpellAbilityStackInstance si : game.getStack()) if (si.getId() == sid) return si;
        return null;
    }

    /** Instance id the client knows for a stack item (the spell card, or the ability object). */
    public int stackIid(SpellAbilityStackInstance si) {
        if (si.isSpell() && si.getSourceCard() != null && si.getSourceCard().isInZone(ZoneType.Stack)) return iid(si.getSourceCard());
        Integer i = iidByKey.get("s" + si.getId());
        return i == null ? 0 : i;
    }

    private int fresh() {
        while (usedIids.contains(nextIid)) nextIid++;
        return nextIid++;
    }

    private void bind(String key, int iid, int zone) {
        iidByKey.put(key, iid);
        zoneByKey.put(key, zone);
        usedIids.add(iid);
    }

    // ---- Forge event hints -------------------------------------------------------------------------------------

    /** Collects what a snapshot diff cannot see (damage sources, zone-change reasons, resolution). Game thread. */
    public void onEvent(GameEvent ev) {
        if (game.getStack().isResolving() && !game.getStack().isEmpty()) {
            SpellAbilityStackInstance top = game.getStack().peek();
            if (top != null && top.getId() != resolutionStartedFor) {
                resolutionStartedFor = top.getId();
                int sid = stackIid(top);
                if (sid != 0) earlyAnns.add(resolution(AnnotationType.AnnotationType_ResolutionStart, sid, top.getSpellAbility()));
            }
        }
        if (ev instanceof forge.game.event.GameEventSpellAbilityCast e && e.si() != null) {
            for (SpellAbilityStackInstance si : game.getStack()) {
                if (si.getId() != e.si().getId() || si.isTrigger()) continue;
                SpellAbility sa = si.getSpellAbility();
                Card host = si.getSourceCard();
                boolean spell = sa.isSpell();
                int ag = !spell || sa.getAlternativeCost() != null ? abilityGrp.applyAsInt(sa) : 0;
                pendingActions.add(new PendingAction(si.getId(), spell && host != null ? host.getId() : -1,
                        si.getActivatingPlayer() == null ? HUMAN_SEAT : seatOf(si.getActivatingPlayer()),
                        spell ? com.wizards.mtga.gre.ActionType.ActionType_Cast : com.wizards.mtga.gre.ActionType.ActionType_Activate, ag));
            }
        }
        if (ev instanceof GameEventSpellResolved e) {
            SpellAbility sa = findStackSa(e);
            SpellAbilityStackInstance top = game.getStack().isEmpty() ? null : game.getStack().peek();
            if (top != null) {
                int sid = stackIid(top);
                if (sid != 0) lateAnns.add(resolution(AnnotationType.AnnotationType_ResolutionComplete, sid, top.getSpellAbility()));
            }
            if (sa != null && sa.isSpell() && sa.getHostCard() != null) categoryHint.put(sa.getHostCard().getId(), e.hasFizzled() ? "Countered" : "Resolve");
        } else if (ev instanceof GameEventSpellRemovedFromStack e) {
            // fired after EVERY stack item leaves, resolved or not: only a spell that did not resolve was countered. An
            // ability's removal named its host (Devourer of Destiny's own cast trigger), and that stale "Countered"
            // labelled the creature's later Stack -> Battlefield move: the client burned it instead of resolving it
            CardView host = e.sa() == null || !e.sa().isSpell() ? null : e.sa().getHostCard();
            if (host != null && !"Resolve".equals(categoryHint.get(host.getId()))) categoryHint.put(host.getId(), "Countered");
        } else if (ev instanceof GameEventCardSacrificed e) {
            if (e.card() != null) categoryHint.put(e.card().getId(), "Sacrifice");
        } else if (ev instanceof GameEventCardDamaged e) {
            if (e.card() == null) return;
            Integer victim = iidByKey.get("c" + e.card().getId());
            if (victim == null) return;
            damagedCards.add(e.card().getId());
            AnnotationInfo.Builder a = ann(AnnotationType.AnnotationType_DamageDealt, sourceIid(e.source()), victim);
            a.addDetails(intDetail("damage", e.amount()));
            a.addDetails(intDetail("type", inCombatDamage() ? 1 : 2));
            earlyAnns.add(a.build());
        } else if (ev instanceof GameEventPlayerDamaged e) {
            int seat = e.target() != null && e.target().getId() == human.getId() ? HUMAN_SEAT : AI_SEAT;
            int src = sourceIid(e.source());
            lifeAffector.put(seat, src);
            AnnotationInfo.Builder a = ann(AnnotationType.AnnotationType_DamageDealt, src, seat);
            a.addDetails(intDetail("damage", e.amount())).addDetails(intDetail("type", e.combat() ? 1 : 2));
            playerDamage.computeIfAbsent(seat, k -> new ArrayList<>()).add(a.build());
        }
    }

    private boolean inCombatDamage() {
        PhaseType pt = game.getPhaseHandler().getPhase();
        return (pt == PhaseType.COMBAT_DAMAGE || pt == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE) && game.getStack().isEmpty();
    }

    private SpellAbility findStackSa(GameEventSpellResolved e) {
        SpellAbilityStackInstance top = game.getStack().isEmpty() ? null : game.getStack().peek();
        return top == null ? null : top.getSpellAbility();
    }

    private int sourceIid(CardView v) {
        if (v == null) return 0;
        Integer i = iidByKey.get("c" + v.getId());
        return i == null ? 0 : i;
    }

    private AnnotationInfo resolution(AnnotationType type, int stackIid, SpellAbility sa) {
        int grp = sa == null ? 0 : sa.isSpell() && sa.getHostCard() != null ? grpIdOf(sa.getHostCard()) : abilityGrp.applyAsInt(sa);
        return ann(type, stackIid, stackIid).addDetails(intDetail("grpid", grp)).build();
    }

    // ---- snapshot ----------------------------------------------------------------------------------------------

    private final Map<Integer, Integer> tokenGrp = new HashMap<>();

    /** True while an adventurer card is its adventure half (cast as the adventure, on the stack). */
    static boolean onAdventureFace(Card c) {
        return c.isAdventureCard() && c.getCurrentStateName() == forge.card.CardStateName.Secondary;
    }

    public int grpIdOf(Card c) {
        // face-down permanents (morph, manifest, disguise, cloak) have no name in Forge: the client's own
        // "Face-Down Creature" (a 2/2 without abilities) stands for them
        if (c.isFaceDown()) {
            int fd = db.grpId("Face-Down Creature");
            if (fd != 0) return fd;
        }
        if (onAdventureFace(c)) {
            int face = db.linkedFace(db.grpId(c.getState(forge.card.CardStateName.Original).getName()));
            if (face != 0) return face;
        }
        if (c.isEmblem()) {      // the client builds an emblem from the card that made it (objectSourceGrpId); 0 = nothing to show
            Card src = visibleSource(c);
            int sg = src == null || src == c ? 0 : grpIdOf(src);
            return sg != 0 && !db.emblemAbilityIds(sg).isEmpty() ? sg : 0;
        }
        if (c.isToken()) {
            return tokenGrp.computeIfAbsent(c.getId(), k -> {
                SpellAbility src = c.getTokenSpawningAbility();
                int srcGrp = src == null || src.getHostCard() == null ? 0 : grpIdOf(src.getHostCard());
                int t = db.tokenGrpId(c.getName(), srcGrp, c.getNetPower(), c.getNetToughness());
                return t != 0 ? t : db.grpId(c.getName());      // token copies of real cards
            });
        }
        // a Room in play is its current half in Forge ("Unholy Annex" once only that door is unlocked): the client always
        // shows the whole card (both halves, locked ones marked by a designation), so it needs the "A // B" printing
        String name = c.isRoom() && c.hasState(forge.card.CardStateName.Original) ? c.getState(forge.card.CardStateName.Original).getName() : c.getName();
        Integer g = grpByName.get(name);
        if (g == null) {
            g = db.grpId(name);
            grpByName.put(name, g);
        }
        return g;
    }

    private record IdChange(int oldId, int newId, int srcZone, int dstZone, Card card) { }

    private static final class Snapshot {
        final Map<Integer, GameObjectInfo> objs = new LinkedHashMap<>();
        final Map<Integer, ZoneInfo> zones = new LinkedHashMap<>();
        final Map<Integer, PlayerInfo> players = new LinkedHashMap<>();
        final Map<String, AnnotationInfo.Builder> persistent = new LinkedHashMap<>();
        final List<IdChange> idChanges = new ArrayList<>();
        final List<Integer> createdTokens = new ArrayList<>();
        final Map<Integer, Integer> createdAbilities = new LinkedHashMap<>();   // ability iid -> parent iid
        final Map<Integer, Map<Integer, Integer>> counters = new HashMap<>();   // iid -> counter type -> count
        final Map<Integer, Integer> doors = new HashMap<>();    // Room iid -> unlocked doors (bit 1 left, 2 right)
        // id bookkeeping, applied only when the snapshot is sent (commit)
        final Map<String, int[]> binds = new HashMap<>();       // key -> {iid, zone}
        final Map<Integer, Integer> newForge = new HashMap<>();  // iid -> forge card id
        final Map<Integer, Integer> newStack = new HashMap<>();  // iid -> stack instance id
        final Set<String> liveStack = new HashSet<>();
        final Set<Integer> forgeIds = new HashSet<>();
        final Set<Integer> tokens = new HashSet<>();
        TurnInfo turn;
    }

    private final Map<forge.game.mana.Mana, List<Integer>> manaIds = new java.util.IdentityHashMap<>();
    private int nextManaId = 1;

    /** One unit of floating mana and its stable id (PlayerInfo.manaPool, AutoTapAction.manaId). */
    public record PoolMana(forge.game.mana.Mana mana, int id) { }

    /**
     * The player's floating mana in pool order, one entry per unit. Forge can hold the same Mana object several times
     * (Eldrazi Temple's restricted {C}{C}), and the client keys its pool by manaId (a repeated id crashes its state
     * merge with a duplicate-key exception), so the n-th occurrence of an object gets its own id.
     */
    public List<PoolMana> pool(Player p) {
        List<PoolMana> out = new ArrayList<>();
        Map<forge.game.mana.Mana, Integer> seen = new java.util.IdentityHashMap<>();
        for (forge.game.mana.Mana m : p.getManaPool()) {
            int n = seen.merge(m, 1, Integer::sum) - 1;
            List<Integer> ids = manaIds.computeIfAbsent(m, k -> new ArrayList<>());
            if (n == ids.size() && n > 0) System.out.println("[mtga] pool holds one mana object " + (n + 1) + "x: " + m);
            while (ids.size() <= n) ids.add(nextManaId++);
            out.add(new PoolMana(m, ids.get(n)));
        }
        return out;
    }

    public static com.wizards.mtga.gre.ManaColor manaColor(byte color) {
        if ((color & forge.card.MagicColor.WHITE) != 0) return com.wizards.mtga.gre.ManaColor.ManaColor_White;
        if ((color & forge.card.MagicColor.BLUE) != 0) return com.wizards.mtga.gre.ManaColor.ManaColor_Blue;
        if ((color & forge.card.MagicColor.BLACK) != 0) return com.wizards.mtga.gre.ManaColor.ManaColor_Black;
        if ((color & forge.card.MagicColor.RED) != 0) return com.wizards.mtga.gre.ManaColor.ManaColor_Red;
        if ((color & forge.card.MagicColor.GREEN) != 0) return com.wizards.mtga.gre.ManaColor.ManaColor_Green;
        return com.wizards.mtga.gre.ManaColor.ManaColor_Colorless;
    }

    private int idInOrZero(Card c) {
        Integer i = iidByKey.get("c" + c.getId());
        return i == null ? 0 : i;
    }

    /** Instance id of c as of snapshot s (its pending id if it moved in this snapshot). */
    private int idIn(Snapshot s, Card c) {
        int[] b = s == null ? null : s.binds.get("c" + c.getId());
        return b != null ? b[0] : iid(c);
    }

    private void commit(Snapshot s) {
        for (Map.Entry<String, int[]> e : s.binds.entrySet()) bind(e.getKey(), e.getValue()[0], e.getValue()[1]);
        forgeByIid.putAll(s.newForge);
        stackByIid.putAll(s.newStack);
        iidByKey.keySet().removeIf(k -> k.startsWith("s") && !s.liveStack.contains(k));
        sentTokens.clear();
        sentTokens.addAll(s.tokens);
    }

    private final Set<Integer> sentTokens = new HashSet<>();
    private int unstable;

    private Snapshot snapshot() {
        Snapshot s = new Snapshot();
        for (int seat : new int[] {HUMAN_SEAT, AI_SEAT}) {
            Player p = seat == HUMAN_SEAT ? human : ai;
            addZone(s, ZoneType.Library, seat, p.getCardsIn(ZoneType.Library));
            addZone(s, ZoneType.Hand, seat, p.getCardsIn(ZoneType.Hand));
            addZone(s, ZoneType.Graveyard, seat, p.getCardsIn(ZoneType.Graveyard));
            PlayerInfo.Builder pi = PlayerInfo.newBuilder()
                    .setLifeTotal(p.getLife()).setStartingLifeTotal(p.getStartingLife()).setSystemSeatNumber(seat)
                    .setStatus(p.hasLost() ? PlayerStatus.PlayerStatus_PendingLoss : PlayerStatus.PlayerStatus_InGame)
                    .setMaxHandSize(Math.max(0, p.getMaxHandSize())).setTeamId(seat).setControllerSeatId(seat)
                    .setControllerType(ControllerType.ControllerType_Player)
                    .setMulliganCount(mulligans[seat])
                    .setTurnNumber(p.getTurn());
            if (mulliganPending[seat]) pi.setPendingMessageType(com.wizards.mtga.gre.ClientMessageType.ClientMessageType_MulliganResp);
            else if (startPending[seat]) pi.setPendingMessageType(com.wizards.mtga.gre.ClientMessageType.ClientMessageType_ChooseStartingPlayerResp);
            for (PoolMana pm : pool(p)) {
                Card src = pm.mana().getSourceCard();
                pi.addManaPool(com.wizards.mtga.gre.ManaInfo.newBuilder().setManaId(pm.id()).setColor(manaColor(pm.mana().getColor())).setCount(1)
                        .setSrcInstanceId(src == null ? 0 : idInOrZero(src)));
            }
            Map<Integer, Integer> pc = new LinkedHashMap<>();       // energy, poison, experience, rad...: counters on the player
            for (forge.game.card.CounterType ct : p.getCounters().elementSet()) {
                int n = p.getCounters().count(ct);
                int type = counterType(ct);
                if (n > 0 && type != 0) pc.merge(type, n, Integer::sum);
            }
            if (!pc.isEmpty()) {
                s.counters.put(seat, pc);
                for (Map.Entry<Integer, Integer> e : pc.entrySet()) {
                    s.persistent.put("counter:" + seat + ":" + e.getKey(), AnnotationInfo.newBuilder().setAffectorId(seat).addAffectedIds(seat)
                            .addType(AnnotationType.AnnotationType_Counter)
                            .addDetails(intDetail("count", e.getValue())).addDetails(intDetail("counter_type", e.getKey())));
                }
            }
            if (p.hasBlessing()) {      // Ascend: the client draws the city's blessing emblem from this designation
                s.persistent.put("blessing:" + seat, AnnotationInfo.newBuilder().setAffectorId(seat).addAffectedIds(seat)
                        .addType(AnnotationType.AnnotationType_Designation)
                        .addDetails(intDetail("DesignationType", com.wizards.mtga.gre.Designation.Designation_CitysBlessing_VALUE)));
            }
            s.players.put(seat, pi.build());
        }
        addZone(s, ZoneType.Battlefield, 0, game.getCardsIn(ZoneType.Battlefield));
        if (!hideStack) addStack(s);
        addZone(s, ZoneType.Exile, 0, game.getCardsIn(ZoneType.Exile));
        findCompanions();
        List<Card> command = new ArrayList<>();
        for (Card c : game.getCardsIn(ZoneType.Command)) if (!isCompanionCard(c)) command.add(c);
        lingerScheme(command);
        addZone(s, ZoneType.Command, 0, command);
        // the client marks a commander by a designation on the card (it keeps it at the command zone's front, shows the tax)
        for (Player p : new Player[] {human, ai}) {
            for (Card c : p.getCommanders()) {
                ZoneType z = c.getZone() == null ? null : c.getZone().getZoneType();
                if (z != ZoneType.Command && z != ZoneType.Battlefield && z != ZoneType.Stack && z != ZoneType.Graveyard && z != ZoneType.Exile) continue;
                int cid = idIn(s, c);
                if (!s.objs.containsKey(cid)) continue;
                s.persistent.put("commander:" + c.getId(), AnnotationInfo.newBuilder().setAffectorId(seatOf(p)).addAffectedIds(cid)
                        .addType(AnnotationType.AnnotationType_Designation)
                        .addDetails(intDetail("DesignationType", com.wizards.mtga.gre.Designation.Designation_Commander_VALUE)));
            }
        }
        // the dungeon a player is in: the client's room picker reads the dungeon, its instance and the current room from this annotation
        for (Card c : command) {
            if (!c.getType().isDungeon()) continue;
            int cid = idIn(s, c), grp = grpIdOf(c), room = db.roomAbility(grp, c.getCurrentRoom());
            if (!s.objs.containsKey(cid) || grp == 0) continue;
            int seat = seatOf(c.getController());
            s.persistent.put("dungeon:" + seat, AnnotationInfo.newBuilder().setAffectorId(seat).addAffectedIds(seat)
                    .addType(AnnotationType.AnnotationType_DungeonStatus)
                    .addDetails(intDetail("CurrentDungeon", grp)).addDetails(intDetail("CurrentDungeonZCID", cid)).addDetails(intDetail("CurrentRoom", room)));
        }
        addExiledUnder(s);
        addAttachments(s);
        for (Map.Entry<String, AnnotationInfo.Builder> e : extraPersistent.entrySet()) s.persistent.put(e.getKey(), e.getValue());
        s.zones.put(Z_LIMBO, ZoneInfo.newBuilder().setZoneId(Z_LIMBO).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Limbo)
                .setVisibility(Visibility.Visibility_Public).build());
        long now = System.currentTimeMillis();
        revealedCards.removeIf(r -> r.until() < now || r.card().getGame() != game);
        announceReveals.removeIf(id -> revealedCards.stream().noneMatch(r -> r.iid() == id));
        ZoneInfo.Builder revealedMine = ZoneInfo.newBuilder().setZoneId(Z_REVEALED).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Revealed)
                .setVisibility(Visibility.Visibility_Public).setOwnerSeatId(HUMAN_SEAT);
        ZoneInfo.Builder revealedTheirs = ZoneInfo.newBuilder().setZoneId(Z_REVEALED_OPP).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Revealed)
                .setVisibility(Visibility.Visibility_Public).setOwnerSeatId(AI_SEAT);
        for (Revealed r : revealedCards) {
            int zid = revealedZone(r.card());
            GameObjectInfo.Builder o = objectInfo(s, r.card(), r.iid(), zid, ZoneType.Exile, false).toBuilder()
                    .setType(GameObjectType.GameObjectType_RevealedCard).setVisibility(Visibility.Visibility_Public).clearViewers();
            s.objs.put(r.iid(), o.build());
            (zid == Z_REVEALED ? revealedMine : revealedTheirs).addObjectInstanceIds(r.iid());
        }
        Set<Integer> stillKnown = new HashSet<>();
        for (Integer id : new ArrayList<>(knownCards)) {
            Card c = game.findById(id);
            boolean topOfLibrary = c != null && keptRevealed(c);
            boolean theirHand = c != null && c.isInZone(ZoneType.Hand) && seatOf(c.getOwner()) != HUMAN_SEAT;
            if (!topOfLibrary && !theirHand) { knownCards.remove(id); continue; }
            if (!theirHand) continue;
            int iid = knownIid.computeIfAbsent(id, k -> { int n = fresh(); usedIids.add(n); return n; });
            stillKnown.add(id);
            s.objs.put(iid, objectInfo(s, c, iid, zoneId(ZoneType.Hand, seatOf(c.getOwner())), ZoneType.Exile, false).toBuilder()
                    .setType(GameObjectType.GameObjectType_RevealedCard).setVisibility(Visibility.Visibility_Public).clearViewers().build());
        }
        knownIid.keySet().retainAll(stillKnown);
        s.zones.put(Z_REVEALED, revealedMine.build());
        // zones the client looks up unconditionally (LocalSideboard in library searches, OpponentRevealed, PhasedOut)
        s.zones.put(Z_REVEALED_OPP, revealedTheirs.build());
        s.zones.put(Z_PHASED_OUT, ZoneInfo.newBuilder().setZoneId(Z_PHASED_OUT).setType(com.wizards.mtga.gre.ZoneType.ZoneType_PhasedOut)
                .setVisibility(Visibility.Visibility_Public).build());
        // a companion is revealed information: both seats see the card in its owner's sideboard zone (the client draws it by that player's hand)
        for (Player p : new Player[] {human, ai}) {
            int zid = seatOf(p) == HUMAN_SEAT ? Z_SIDEBOARD : Z_SIDEBOARD_OPP;
            ZoneInfo.Builder sideboard = ZoneInfo.newBuilder().setZoneId(zid).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Sideboard)
                    .setVisibility(Visibility.Visibility_Public).setOwnerSeatId(seatOf(p));
            Card c = companionOf.get(p);
            if (isCompanionCard(c)) {
                int iid = track(s, c, zid);
                sideboard.addObjectInstanceIds(iid);
                s.objs.put(iid, objectInfo(s, c, iid, zid, ZoneType.Sideboard, false));
                // the client keeps the card next to the hand (AltCardHolderCalculator) when the card itself carries the designation
                s.persistent.put("companion-card:" + seatOf(p), AnnotationInfo.newBuilder().setAffectorId(seatOf(p)).addAffectedIds(iid)
                        .addType(AnnotationType.AnnotationType_Designation)
                        .addDetails(intDetail("DesignationType", com.wizards.mtga.gre.Designation.Designation_Companion_VALUE))
                        .addDetails(intDetail("grpid", grpIdOf(c))));
            }
            s.zones.put(zid, sideboard.build());
        }
        for (Player p : new Player[] {human, ai}) {       // the designation names the card even once it is in the hand
            Card c = companionOf.get(p);
            if (c == null) continue;
            s.persistent.put("companion:" + seatOf(p), AnnotationInfo.newBuilder().setAffectorId(seatOf(p)).addAffectedIds(seatOf(p))
                    .addType(AnnotationType.AnnotationType_Designation)
                    .addDetails(intDetail("DesignationType", com.wizards.mtga.gre.Designation.Designation_Companion_VALUE))
                    .addDetails(intDetail("grpid", grpIdOf(c))));
        }
        s.turn = turnInfo();
        return s;
    }

    // ---- revealed cards ------------------------------------------------------------------------------------------
    // Arena shows a reveal without asking anything: each revealed card gets a RevealedCard object (own instance id, in the Revealed zone
    // of the card's owner) and a CardRevealed annotation naming it; the client plays the reveal and nobody has to dismiss it. The objects
    // go away again a few seconds later (the next diff after that), which the client sees as RevealedCardDeleted.

    private static final long REVEAL_MS = 3500;

    private record Revealed(Card card, int iid, int sourceZone, long until) { }

    private final List<Revealed> revealedCards = new ArrayList<>();
    private final List<Integer> announceReveals = new ArrayList<>();      // iids whose CardRevealed annotation has not gone out yet
    private int revealSourceSeat;
    // A revealed card stays known while it is in the opponent's hand (the client shows it face-up there: a RevealedCard object in the
    // hand's zone, RevealedCardCreated/Deleted for the client) or on top of a library (the top object is shown to this client); it is
    // forgotten when it goes anywhere else. The same card keeps its object while it stays; moving library -> hand makes the hand one.
    private final Set<Integer> knownCards = new HashSet<>();
    private final Map<Integer, Integer> knownIid = new HashMap<>();      // forge card id -> iid of its hand RevealedCard object
    private final Set<Integer> createdReveals = new HashSet<>();      // hand RevealedCard objects whose RevealedCardCreated went out

    /** A revealed card on top of a library keeps showing its face to this client. */
    private boolean keptRevealed(Card c) {
        if (!knownCards.contains(c.getId()) || !c.isInZone(ZoneType.Library)) return false;
        CardCollectionView lib = c.getOwner().getCardsIn(ZoneType.Library);
        return !lib.isEmpty() && lib.get(0).getId() == c.getId();
    }

    /** Reveals these cards (hand, library...) to this client with Arena's non-blocking mechanism; {@code revealer}: the player who reveals them. */
    public void revealNow(Iterable<Card> cards, Player revealer) {
        long until = System.currentTimeMillis() + REVEAL_MS;
        revealSourceSeat = seatOf(revealer);
        for (Card c : cards) {
            int src = c.isInZone(ZoneType.Hand) ? zoneId(ZoneType.Hand, seatOf(c.getOwner())) : c.isInZone(ZoneType.Library) ? zoneId(ZoneType.Library, seatOf(c.getOwner())) : zoneId(c.getZone().getZoneType(), seatOf(c.getOwner()));
            int iid = fresh();
            usedIids.add(iid);
            revealedCards.add(new Revealed(c, iid, src, until));
            announceReveals.add(iid);
            knownCards.add(c.getId());
        }
    }

    private int revealedZone(Card c) { return seatOf(c.getOwner()) == HUMAN_SEAT ? Z_REVEALED : Z_REVEALED_OPP; }

    // ---- companions ----------------------------------------------------------------------------------------------
    // A companion waits in the player's command zone in Forge. Arena keeps it in the player's Sideboard zone, marks the player with a
    // Companion designation (the card's grpId) and offers the {3} move to hand as a special action on the card; the client then draws
    // the card next to the hand (AltCardHolderCalculator). The opponent's companion is only the designation.

    private final Map<Player, Card> companionOf = new HashMap<>();

    /** A companion card in the command zone (not Forge's effect card). */
    private static boolean isCompanionCard(Card c) {
        return c != null && !c.isImmutable() && c.isInZone(ZoneType.Command) && c.hasKeyword(forge.game.keyword.Keyword.COMPANION);
    }

    /** Remembers each player's companion once it shows up; it stays the player's companion after it went to the hand. */
    private void findCompanions() {
        for (Player p : new Player[] {human, ai}) {
            Card known = companionOf.get(p);
            if (known != null && known.getGame() != game) companionOf.remove(p);
            if (companionOf.containsKey(p)) continue;
            for (Card c : p.getCardsIn(ZoneType.Command)) if (isCompanionCard(c)) { companionOf.put(p, c); break; }
        }
    }

    /** The "{3}: put the companion into your hand" ability of a companion in the command zone. */
    public boolean isCompanionMove(SpellAbility sa) {
        return sa != null && isCompanionCard(sa.getHostCard()) && sa.getApi() == forge.game.ability.ApiType.ChangeZone;
    }

    /** The card's own Companion ability (the client's id of "Companion — ..."), the special action's ability. */
    public int companionAbilityId(Card c) {
        for (CardDb.Ability a : db.abilities(grpIdOf(c))) if (a.text() != null && a.text().startsWith("Companion")) return a.id();
        return 202;
    }

    private static ZoneInfo.Builder zoneHeader(ZoneType t, int seat, int zid) {
        ZoneInfo.Builder z = ZoneInfo.newBuilder().setZoneId(zid).setOwnerSeatId(seat);
        switch (t) {
            case Library: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Library).setVisibility(Visibility.Visibility_Hidden); break;
            case Hand:
                z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Hand).setVisibility(Visibility.Visibility_Private);
                z.addViewers(seat);
                break;
            case Graveyard: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Graveyard).setVisibility(Visibility.Visibility_Public); break;
            case Battlefield: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Battlefield).setVisibility(Visibility.Visibility_Public); break;
            case Stack: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Stack).setVisibility(Visibility.Visibility_Public); break;
            case Exile: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Exile).setVisibility(Visibility.Visibility_Public); break;
            default: z.setType(com.wizards.mtga.gre.ZoneType.ZoneType_Command).setVisibility(Visibility.Visibility_Public); break;
        }
        return z;
    }

    /** Scheme ids in the command zone of the last snapshot, and the newest scheme set in motion (Archenemy). */
    private final Set<Integer> schemesBefore = new HashSet<>();
    private Card lastScheme;

    /**
     * A scheme that is not ongoing is abandoned the moment it resolves, which would show its emblem for a blink: the newest scheme stays in the
     * command zone list until the next one is set in motion, so the player can read what Sparky just did (and what an ongoing one still does).
     */
    private void lingerScheme(List<Card> command) {
        Set<Integer> now = new HashSet<>();
        Card fresh = null;
        for (Card c : command) {
            if (!c.getType().isScheme()) continue;
            now.add(c.getId());
            if (!schemesBefore.contains(c.getId())) fresh = c;
        }
        schemesBefore.clear();
        schemesBefore.addAll(now);
        if (fresh != null) lastScheme = fresh;
        if (lastScheme != null && lastScheme.getGame() == game && !now.contains(lastScheme.getId())) command.add(lastScheme);
    }

    private void addZone(Snapshot s, ZoneType t, int seat, Iterable<Card> cards) {
        int zid = zoneId(t, seat);
        ZoneInfo.Builder z = zoneHeader(t, seat, zid);
        boolean hidden = (t == ZoneType.Library) || (t == ZoneType.Hand && seat != HUMAN_SEAT);
        for (Card c : cards) {
            if (c.isImmutable() && !(c.isEmblem() && grpIdOf(c) != 0)) continue;       // Forge effect cards have no client representation; emblems do (built from the card that made them)
            if ((c.getType().isScheme() || c.getType().isVanguard()) && grpIdOf(c) == 0) continue;       // a scheme / avatar the client has no row for (card DB built before Archenemy / Momir)
            int iid = track(s, c, zid);
            z.addObjectInstanceIds(iid);
            s.objs.put(iid, objectInfo(s, c, iid, zid, t, hidden && !revealed.contains(c.getId()) && !keptRevealed(c)));
            if (t == ZoneType.Battlefield && c.isRoom()) addRoomHalves(s, z, c, iid, zid);
        }
        s.zones.put(zid, z.build());
    }

    /** Instance ids of a Room's two halves on the battlefield, by the Room's own instance id (left, right). */
    private final Map<Integer, int[]> roomHalfIids = new HashMap<>();

    /**
     * The client builds a Room from the card plus two child objects, RoomLeft and RoomRight (linked to it by parentId): which half is locked is
     * read from the parent's door designations through them. Without the children it cannot tell the halves apart and marks both as locked.
     */
    private void addRoomHalves(Snapshot s, ZoneInfo.Builder z, Card c, int parentIid, int zid) {
        int grp = grpIdOf(c);
        int[] faces = {db.linkedFace(grp), db.secondFace(grp)};
        if (faces[0] == 0 || faces[1] == 0) return;
        int[] ids = roomHalfIids.computeIfAbsent(parentIid, k -> { int a = fresh(); usedIids.add(a); int b = fresh(); usedIids.add(b); return new int[] {a, b}; });
        for (int i = 0; i < 2; i++) {
            z.addObjectInstanceIds(ids[i]);
            GameObjectInfo.Builder half = GameObjectInfo.newBuilder().setInstanceId(ids[i]).setZoneId(zid)
                    .setType(i == 0 ? GameObjectType.GameObjectType_RoomLeft : GameObjectType.GameObjectType_RoomRight)
                    .setGrpId(faces[i]).setOverlayGrpId(faces[i]).setParentId(parentIid)
                    .setOwnerSeatId(seatOf(c.getOwner())).setControllerSeatId(seatOf(c.getController()))
                    .setVisibility(Visibility.Visibility_Public);
            for (CardDb.Ability a : db.abilities(faces[i])) half.addUniqueAbilities(com.wizards.mtga.gre.UniqueAbilityInfo.newBuilder().setId(uniqueAbilityId(ids[i], a.id())).setGrpId(a.id()));
            s.objs.put(ids[i], half.build());
        }
    }

    private void addStack(Snapshot s) {
        ZoneInfo.Builder z = zoneHeader(ZoneType.Stack, 0, Z_STACK);
        Set<Integer> stackCards = new HashSet<>();
        for (Card c : game.getCardsIn(ZoneType.Stack)) stackCards.add(c.getId());
        for (SpellAbilityStackInstance si : game.getStack()) {
            Card host = si.getSourceCard();
            if (si.isSpell() && (host == null || !stackCards.contains(host.getId()))) continue;    // resolving: card already left the stack
            if (si.isSpell() && stackCards.remove(host.getId())) {
                int iid = track(s, host, Z_STACK);
                z.addObjectInstanceIds(iid);
                s.objs.put(iid, objectInfo(s, host, iid, Z_STACK, ZoneType.Stack, false));
                addTargetSpecs(s, iid, si);
                continue;
            }
            if (isRuleTrigger(host)) continue;      // the client has no ability for it: its effects show as life, counters and mills
            String key = "s" + si.getId();
            s.liveStack.add(key);
            Integer iid = iidByKey.get(key);
            Card shown = visibleSource(host);
            int parent = shown == null || shown.isImmutable() ? 0 : idIn(s, shown);
            if (iid == null) {
                iid = fresh();
                s.binds.put(key, new int[] {iid, Z_STACK});
                s.newStack.put(iid, si.getId());
                s.createdAbilities.put(iid, parent);
            }
            z.addObjectInstanceIds(iid);
            s.objs.put(iid, abilityInfo(si, iid, parent));
            addTargetSpecs(s, iid, si);
        }
        for (Card c : game.getCardsIn(ZoneType.Stack)) {      // spell copies etc. not matched to a stack item
            if (!stackCards.contains(c.getId())) continue;
            int iid = track(s, c, Z_STACK);
            z.addObjectInstanceIds(iid);
            s.objs.put(iid, objectInfo(s, c, iid, Z_STACK, ZoneType.Stack, false));
        }
        s.zones.put(Z_STACK, z.build());
    }

    /**
     * Persistent TargetSpec annotation per targeting part of a stack object (affector = the spell/ability, affected = what it
     * targets, details index (1-based) and abilityGrpId): the client draws the target arrow and the "targets X" text from it.
     */
    private void addTargetSpecs(Snapshot s, int stackIid, SpellAbilityStackInstance si) {
        int index = 0;
        for (SpellAbility sa = si.getSpellAbility(); sa != null; sa = sa.getSubAbility()) {
            if (!sa.usesTargeting() || sa.getTargets() == null) continue;
            index++;
            List<Integer> ids = new ArrayList<>();
            for (Card c : sa.getTargets().getTargetCards()) {
                int id = idIn(s, c);
                if (id != 0 && s.objs.containsKey(id)) ids.add(id);
            }
            for (Player p : sa.getTargets().getTargetPlayers()) ids.add(seatOf(p));
            for (SpellAbility t : sa.getTargets().getTargetSpells()) {
                Card tc = t.getHostCard();
                int id = tc == null ? 0 : idIn(s, tc);
                if (id != 0 && s.objs.containsKey(id)) ids.add(id);
            }
            if (ids.isEmpty()) continue;
            AnnotationInfo.Builder a = AnnotationInfo.newBuilder().setAffectorId(stackIid).addAllAffectedIds(ids)
                    .addType(AnnotationType.AnnotationType_TargetSpec)
                    .addDetails(intDetail("index", index)).addDetails(intDetail("abilityGrpId", abilityGrp.applyAsInt(sa)));
            s.persistent.put("target:" + stackIid + ":" + index, a);
        }
    }

    /**
     * A token on the battlefield, or a dungeon in the command zone (no DungeonStatus room tracker yet), appears out of nowhere: announced as
     * TokenCreated. So does a spell copy cast straight onto the stack (Isochron Scepter's copy of the imprinted card).
     */
    private boolean appearsFromNowhere(Card c, int zid) {
        return gsId > 0 && (c.isToken() && (zid == Z_BF || zid == Z_STACK) || (c.getType().isDungeon() || c.getType().isScheme()) && zid == Z_COMMAND);
    }

    /** Instance id for c now in zone zid; a zone change allocates a new id (recorded as an IdChange). */
    private int track(Snapshot s, Card c, int zid) {
        String key = "c" + c.getId();
        s.forgeIds.add(c.getId());
        if (c.isToken()) s.tokens.add(c.getId());
        Integer cur = iidByKey.get(key);
        if (cur == null) {
            int id = c.getId() + 100;
            if (id >= 1000 || usedIids.contains(id)) id = fresh();
            usedIids.add(id);
            s.binds.put(key, new int[] {id, zid});
            s.newForge.put(id, c.getId());
            if (appearsFromNowhere(c, zid)) s.createdTokens.add(id);
            return id;
        }
        int was = zoneByKey.getOrDefault(key, -1);
        if (was == -1) {      // an id handed out before the card was ever in a state (a prompt naming it, e.g. the kicker question for a copy)
            s.binds.put(key, new int[] {cur, zid});
            if (appearsFromNowhere(c, zid)) s.createdTokens.add(cur);
            return cur;
        }
        if (was == zid) return cur;
        int nid = fresh();
        usedIids.add(nid);
        s.binds.put(key, new int[] {nid, zid});
        s.newForge.put(nid, c.getId());
        s.idChanges.add(new IdChange(cur, nid, was, zid, c));
        return nid;
    }

    private GameObjectInfo objectInfo(Snapshot s, Card c, int iid, int zid, ZoneType t, boolean hidden) {
        int owner = seatOf(c.getOwner()), controller = seatOf(c.getController());
        GameObjectInfo.Builder o = GameObjectInfo.newBuilder().setInstanceId(iid).setZoneId(zid)
                .setType(GameObjectType.GameObjectType_Card)      // GameObjectType_Adventure breaks the client's source lookup (NRE)
                .setOwnerSeatId(owner).setControllerSeatId(controller);
        if (hidden) {
            return o.setVisibility(Visibility.Visibility_Hidden).build();
        }
        int grp = grpIdOf(c);
        o.setGrpId(grp).setOverlayGrpId(grp);
        if (c.isFaceDown()) o.setIsFacedown(true);
        // a scheme (Archenemy) in the command zone is drawn as an emblem of its own card row: an ongoing scheme stays, any other is gone once it resolves
        boolean scheme = c.getType().isScheme() || c.getType().isVanguard();      // Momir Basic's avatar the same
        if (c.isEmblem() || scheme) o.setType(GameObjectType.GameObjectType_Emblem).setObjectSourceGrpId(grp);
        int title = db.titleId(grp);
        if (title != 0) o.setName(title);
        // the client builds a card's abilities (rules text, activation buttons, loyalty) only from uniqueAbilities
        Set<Integer> printed = new HashSet<>();
        List<Integer> abilityIds = new ArrayList<>();
        if (c.isEmblem()) abilityIds.addAll(db.emblemAbilityIds(grp));      // the emblem's own text, not the planeswalker's
        else for (CardDb.Ability a : db.abilities(grp)) abilityIds.add(a.id());
        if (abilityIds.isEmpty() && c.isLand()) abilityIds.addAll(AbilityMap.basicLandAbilities(c));      // intrinsic: not in the card DB
        else if (c.isLand() && t == ZoneType.Battlefield) {      // land types granted by an effect (Yavimaya, Cradle of Growth) add their mana ability
            forge.card.CardTypeView printedType = c.getState(forge.card.CardStateName.Original).getType();
            for (int id : AbilityMap.basicLandAbilities(c)) {
                if (!abilityIds.contains(id) && !printedType.hasSubtype(new String[]{"Plains", "Island", "Swamp", "Mountain", "Forest"}[id - 1001])) abilityIds.add(id);
            }
        }
        // The client refreshes a card (and its door locks) only when its ability id sequence changes, and designations alone do not count:
        // flip the order each time a door opens (none/both: as printed, one open: reversed), so every unlock is a visible change.
        if (t == ZoneType.Battlefield && c.isRoom() && c.getUnlockedRooms().size() % 2 == 1) java.util.Collections.reverse(abilityIds);
        if (t == ZoneType.Battlefield && hasNoAbilities(c)) abilityIds.clear();      // "loses all abilities" (Oko's Elk...)
        for (int id : abilityIds) {
            printed.add(id);
            o.addUniqueAbilities(com.wizards.mtga.gre.UniqueAbilityInfo.newBuilder().setId(uniqueAbilityId(iid, id)).setGrpId(id));
        }
        if (t == ZoneType.Battlefield) {      // keywords granted by effects (haste from Kiki-Jiki, flying from pumps...)
            for (forge.game.keyword.KeywordInterface ki : c.getKeywords()) {
                String k = ki.getOriginal();
                int id = db.keywordAbilityId(k == null ? null : k.split(":")[0]);
                if (id != 0 && printed.add(id)) {
                    o.addUniqueAbilities(com.wizards.mtga.gre.UniqueAbilityInfo.newBuilder().setId(uniqueAbilityId(iid, id)).setGrpId(id));
                }
            }
        }
        if (t == ZoneType.Battlefield && c.isPlaneswalker()) {      // loyalty abilities granted by another card (Ichormoon Gauntlet): printed on the granter as separate ids
            for (SpellAbility sa : c.getSpellAbilities()) {
                if (!sa.isPwAbility()) continue;
                int id = new AbilityMap(db).grantedLoyaltyAbility(sa);
                if (id != 0 && printed.add(id)) {
                    o.addUniqueAbilities(com.wizards.mtga.gre.UniqueAbilityInfo.newBuilder().setId(uniqueAbilityId(iid, id)).setGrpId(id));
                }
            }
        }
        boolean priv = t == ZoneType.Hand || t == ZoneType.Library;
        o.setVisibility(priv ? Visibility.Visibility_Private : Visibility.Visibility_Public);
        if (t == ZoneType.Hand) o.addViewers(owner);
        else if (t == ZoneType.Library) o.addViewers(HUMAN_SEAT);
        if (!scheme) for (forge.card.CardType.CoreType ct : c.getType().getCoreTypes()) {
            CardType m = enumOrNull(CardType.class, "CardType_" + ct.name());
            if (m != null) o.addCardTypes(m);
        }
        for (String st : c.getType().getSubtypes()) {
            SubType m = subTypeOf(st);
            if (m != null) o.addSubtypes(m);
        }
        for (forge.card.CardType.Supertype su : c.getType().getSupertypes()) {
            SuperType m = enumOrNull(SuperType.class, "SuperType_" + su.name());
            if (m != null) o.addSuperTypes(m);
        }
        forge.card.ColorSet cs = c.getColor();
        if (cs.hasWhite()) o.addColor(CardColor.CardColor_White);
        if (cs.hasBlue()) o.addColor(CardColor.CardColor_Blue);
        if (cs.hasBlack()) o.addColor(CardColor.CardColor_Black);
        if (cs.hasRed()) o.addColor(CardColor.CardColor_Red);
        if (cs.hasGreen()) o.addColor(CardColor.CardColor_Green);
        if (c.isCreature()) {
            o.setPower(Int32Value.newBuilder().setValue(c.getNetPower()));
            o.setToughness(Int32Value.newBuilder().setValue(c.getNetToughness()));
        }
        if (c.isPlaneswalker()) {      // off the battlefield it has no counters yet: show the printed starting loyalty (hand, library, stack...)
            int loy = t == ZoneType.Battlefield ? c.getCurrentLoyalty() : baseLoyalty(c);
            o.setLoyalty(UInt32Value.newBuilder().setValue(Math.max(0, loy)));
        }
        if (t == ZoneType.Battlefield) {
            if (c.isCreature() || c.isPlaneswalker()) o.setDamage(c.getDamage());
            o.setIsTapped(c.isTapped());
            o.setHasSummoningSickness(c.isCreature() && c.isSick());
            if (game.getCombat() != null && c.isAttacking()) {
                o.setAttackState(com.wizards.mtga.gre.AttackState.AttackState_Attacking);
                forge.game.GameEntity def = game.getCombat().getDefenderByAttacker(c);
                int target = def instanceof Player dp ? seatOf(dp) : def instanceof Card dc ? idIn(s, dc) : seatOf(c.getController()) == HUMAN_SEAT ? AI_SEAT : HUMAN_SEAT;
                o.getAttackInfoBuilder().setTargetId(target);
                // the client spaces the blockers, draws the combat line and highlights from the attacker's ordered blockers
                for (Card b : game.getCombat().getBlockers(c)) {
                    o.getAttackInfoBuilder().addOrderedBlockers(com.wizards.mtga.gre.OrderedDamageAssignment.newBuilder().setInstanceId(idIn(s, b)));
                }
            }
            if (game.getCombat() != null && game.getCombat().isBlocking(c)) {
                o.setBlockState(com.wizards.mtga.gre.BlockState.BlockState_Blocking);
                for (Card a : game.getCombat().getAttackersBlockedBy(c)) o.getBlockInfoBuilder().addAttackerIds(idIn(s, a));
            }
            if (game.getCombat() != null && c.isAttacking() && game.getCombat().isBlocked(c)) {
                o.setBlockState(com.wizards.mtga.gre.BlockState.BlockState_Blocked);
            }
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (forge.game.card.CounterType ct : c.getCounters().elementSet()) {
                int n = c.getCounters().count(ct);
                int type = counterType(ct);
                if (n <= 0 || type == 0) continue;
                counts.merge(type, n, Integer::sum);
            }
            if (!counts.isEmpty()) {
                s.counters.put(iid, counts);
                for (Map.Entry<Integer, Integer> e : counts.entrySet()) {
                    s.persistent.put("counter:" + iid + ":" + e.getKey(), AnnotationInfo.newBuilder().setAffectorId(iid).addAffectedIds(iid)
                            .addType(AnnotationType.AnnotationType_Counter)
                            .addDetails(intDetail("count", e.getValue())).addDetails(intDetail("counter_type", e.getKey())));
                }
            }
            AnnotationInfo.Builder dev = devotionWord(c, iid);
            if (dev != null) s.persistent.put("devotion:" + iid, dev);
            if (c.hasChosenEvenOdd()) {      // "choose odd or even" (Gollum, Riddle Master): the client prints the choice in blue in the text box
                s.persistent.put("link-parity:" + iid, linkChoose(iid, "Parity", c.getChosenEvenOdd() == forge.game.EvenOdd.Odd ? 1 : 0));
            }
            if (c.hasChosenColor()) {      // chosen color(s): same blue text (Cavern-style "As it enters, choose a color")
                for (String name : c.getChosenColors()) {
                    byte mask = forge.card.MagicColor.fromName(name);
                    for (int i = 0; i < forge.card.MagicColor.WUBRG.length; i++) {
                        if (forge.card.MagicColor.WUBRG[i] == mask) s.persistent.put("link-color:" + iid + ":" + (i + 1), linkChoose(iid, "Color", i + 1));
                    }
                }
            }
            if (c.isRoom()) {       // which doors are open: the client marks the locked half of the card from these
                int mask = 0;
                for (CardStateName door : c.getUnlockedRooms()) mask |= door == CardStateName.LeftSplit ? 1 : door == CardStateName.RightSplit ? 2 : 0;
                s.doors.put(iid, mask);
                for (CardStateName door : c.getUnlockedRooms()) {
                    int type = door == CardStateName.LeftSplit ? com.wizards.mtga.gre.Designation.Designation_LeftUnlocked_VALUE
                            : door == CardStateName.RightSplit ? com.wizards.mtga.gre.Designation.Designation_RightUnlocked_VALUE : 0;
                    if (type == 0) continue;
                    s.persistent.put("door:" + iid + ":" + type, AnnotationInfo.newBuilder().setAffectorId(iid).addAffectedIds(iid)
                            .addType(AnnotationType.AnnotationType_Designation).addDetails(intDetail("DesignationType", type)));
                }
            }
        }
        return o.build();
    }

    /**
     * The "Devotion" ability word the client's badge reads (an icon with the count on the card; the abilities carry the DevotionTo* terms in the
     * card DB, the badge tree matches them and then looks up this word). {@code value} is the devotion to the card's color(s), or the highest one
     * for "a color of your choice" (Nykthos); {@code threshold} is the number a static ability compares it with (Purphoros: less than 5).
     * Null for a card that does not count devotion.
     */
    private AnnotationInfo.Builder devotionWord(Card c, int iid) {
        String devSvar = null, spec = null;
        for (Map.Entry<String, String> e : c.getSVars().entrySet()) {
            if (e.getValue() != null && e.getValue().startsWith("Count$Devotion")) { devSvar = e.getKey(); spec = e.getValue().substring("Count$".length()); break; }
        }
        if (spec == null) return null;
        String[] parts = spec.split("\\.");
        Player ctrl = c.getController();
        if (ctrl == null) return null;
        int value;
        if (parts.length > 1 && parts[1].contains("Chosen") || parts.length < 2) {
            value = 0;
            for (byte col : forge.card.MagicColor.WUBRG) value = Math.max(value, devotionTo(ctrl, col));
        } else {
            byte mask = forge.card.mana.ManaAtom.fromName(parts[1]);
            if (parts[0].equals("DevotionDual") && parts.length > 2) mask |= forge.card.mana.ManaAtom.fromName(parts[2]);
            value = devotionTo(ctrl, mask);
        }
        AnnotationInfo.Builder a = AnnotationInfo.newBuilder().setAffectorId(iid).addAffectedIds(iid)
                .addType(AnnotationType.AnnotationType_AbilityWordActive)
                .addDetails(KeyValuePairInfo.newBuilder().setKey("AbilityWordName").setType(KeyValuePairValueType.KeyValuePairValueType_string).addValueString("Devotion"))
                .addDetails(intDetail("value", value));
        for (forge.game.staticability.StaticAbility st : c.getStaticAbilities()) {
            if (!devSvar.equals(st.getParam("CheckSVar"))) continue;
            String cmp = st.getParamOrDefault("SVarCompare", "");
            if (cmp.length() > 2 && Character.isDigit(cmp.charAt(2))) {
                int n = Integer.parseInt(cmp.substring(2)), op = cmp.startsWith("GT") || cmp.startsWith("LE") ? 1 : 0;
                a.addDetails(intDetail("threshold", n + op));
                break;
            }
        }
        return a;
    }

    /** Mana symbols of the given colors (a mask) in the mana costs of the player's permanents, as Count$Devotion counts them. */
    static int devotionTo(Player p, byte colorMask) {
        int n = p.getDevotionMod();
        for (Card c0 : p.getCardsIn(ZoneType.Battlefield)) {
            for (forge.card.mana.ManaCostShard sh : c0.getManaCost()) if (sh.isColor(colorMask)) n++;
        }
        return n;
    }

    /**
     * Cards exiled with a permanent (imprint: Ugin's Labyrinth; "until it leaves the battlefield": Banisher Priest) are
     * drawn under it by the client: persistent DisplayCardUnderCard annotation, affector = the permanent, affected = the
     * cards (they stay in the Exile zone). The "Disable" detail is required (the client throws without it).
     */
    private void addExiledUnder(Snapshot s) {
        // the game's exile zone, not Card.isInZone: a card returned from exile (Ugin's Labyrinth) stays in the host's
        // exiled list as a stale object that still reports Exile
        Set<Integer> inExile = new HashSet<>();
        for (Card e : game.getCardsIn(ZoneType.Exile)) inExile.add(e.getId());
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            // ids as of this snapshot (a card that just moved has a pending id): only objects the snapshot lists
            int iid = idIn(s, c);
            if (!s.objs.containsKey(iid)) continue;
            java.util.Set<Integer> under = new java.util.TreeSet<>();
            for (forge.game.card.CardCollectionView group : List.of(c.getExiledCards(), c.getUntilLeavesBattlefield())) {
                for (Card e : group) {
                    int eid = inExile.contains(e.getId()) ? idIn(s, e) : 0;
                    if (s.objs.containsKey(eid)) under.add(eid);
                }
            }
            if (under.isEmpty()) continue;
            s.persistent.put("under:" + iid, AnnotationInfo.newBuilder().setAffectorId(iid).addAllAffectedIds(under)
                    .addType(AnnotationType.AnnotationType_DisplayCardUnderCard).addDetails(intDetail("Disable", 0)));
        }
    }

    /**
     * Equipment / Auras / Fortifications: persistent Attachment annotation, affector = the attachment, affected = the
     * permanent or player it is attached to (AttachmentStateAnnotationParser sets AttachedToId / AttachedWithIds, which
     * is what draws the card attached). Without it an equipped creature looked unequipped.
     */
    private void addAttachments(Snapshot s) {
        // the game's battlefield, not Card.isInZone: a creature that just died still holds its equipment (as a stale
        // object that says Battlefield) until state-based actions unattach it
        Set<Integer> inPlay = new HashSet<>();
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) inPlay.add(c.getId());
        for (Card c : game.getCardsIn(ZoneType.Battlefield)) {
            if (!c.isAttachedToEntity()) continue;
            int iid = idIn(s, c);
            if (!s.objs.containsKey(iid)) continue;
            forge.game.GameEntity to = c.getEntityAttachedTo();
            int target = 0;
            if (to instanceof Card host) {
                int hid = inPlay.contains(host.getId()) ? idIn(s, host) : 0;
                GameObjectInfo ho = s.objs.get(hid);
                if (ho != null && ho.getZoneId() == Z_BF) target = hid;
            } else if (to instanceof Player p) {
                target = seatOf(p);
            }
            if (target == 0) continue;
            s.persistent.put("attach:" + iid, AnnotationInfo.newBuilder().setAffectorId(iid).addAffectedIds(target)
                    .addType(AnnotationType.AnnotationType_Attachment));
        }
    }

    /** Emblems/effects are not client objects: their abilities are shown as coming from the card that made them. */
    static Card visibleSource(Card host) {
        if (host != null && host.isImmutable() && host.getEffectSource() != null) return host.getEffectSource();
        return host;
    }

    private static int baseLoyalty(Card c) {
        try { return Integer.parseInt(c.getCurrentState().getBaseLoyalty().trim()); } catch (RuntimeException e) { return 0; }
    }

    /** Forge removed every ability (no spells/abilities besides casting itself, triggers, statics, replacements, keywords). */
    private static boolean hasNoAbilities(Card c) {
        for (SpellAbility sa : c.getSpellAbilities()) if (!sa.isSpell() && !sa.isLandAbility()) return false;
        return c.getManaAbilities().isEmpty() && c.getTriggers().isEmpty() && c.getStaticAbilities().isEmpty()
                && c.getReplacementEffects().isEmpty() && c.getKeywords().isEmpty();
    }

    private final Map<Long, Integer> uniqueAbilityIds = new HashMap<>();
    private int nextUniqueAbility = 1;

    /** Stable per (object, ability) id for GameObjectInfo.uniqueAbilities (the client keys ability instances by it). */
    private int uniqueAbilityId(int iid, int abilityGrp) {
        return uniqueAbilityIds.computeIfAbsent(((long) iid << 32) | abilityGrp, k -> nextUniqueAbility++);
    }

    /**
     * A trigger of a Forge-only effect card (the command zone's Radiation, "Max Speed!"...): an immutable card the client has no card for,
     * so its trigger has no ability to draw. Arena runs these as rules; their results show as life, counter and zone changes.
     */
    private boolean isRuleTrigger(Card host) {
        return host != null && (host.isImmutable() && !host.isEmblem() || host.getType().isScheme() || host.getType().isVanguard()) && grpIdOf(host) == 0;
    }

    private GameObjectInfo abilityInfo(SpellAbilityStackInstance si, int iid, int parent) {
        Card host = visibleSource(si.getSourceCard());
        int ctl = si.getActivatingPlayer() == null ? (host == null ? HUMAN_SEAT : seatOf(host.getController())) : seatOf(si.getActivatingPlayer());
        int srcGrp = host == null ? 0 : grpIdOf(host);
        GameObjectInfo.Builder o = GameObjectInfo.newBuilder().setInstanceId(iid).setZoneId(Z_STACK)
                .setType(GameObjectType.GameObjectType_Ability).setVisibility(Visibility.Visibility_Public)
                .setOwnerSeatId(ctl).setControllerSeatId(ctl)
                .setGrpId(abilityGrp.applyAsInt(si.getSpellAbility())).setObjectSourceGrpId(srcGrp).setParentId(parent);
        int title = db.titleId(srcGrp);
        if (title != 0) o.setName(title);
        return o.build();
    }

    private static final Map<String, Integer> COUNTER_TYPES = new HashMap<>();
    static {
        for (com.wizards.mtga.gre.CounterType t : com.wizards.mtga.gre.CounterType.values()) {
            if (t == com.wizards.mtga.gre.CounterType.UNRECOGNIZED) continue;
            COUNTER_TYPES.put(t.name().replace("CounterType_", "").replace("_", "").toLowerCase(), t.getNumber());
        }
    }

    private static int counterType(forge.game.card.CounterType ct) {
        String n = ct instanceof Enum<?> e ? e.name() : ct.getName();
        return COUNTER_TYPES.getOrDefault(n.replace("_", "").replace(" ", "").toLowerCase(), 0);
    }

    /** The GRE SubType of a Forge subtype name, or null when the client has none. */
    public static SubType subTypeOf(String forgeName) {
        return forgeName == null ? null : enumOrNull(SubType.class, "SubType_" + forgeName.replace(" ", "").replace("'", "").replace("-", ""));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> cls, String name) {
        try { return Enum.valueOf(cls, name); } catch (IllegalArgumentException e) { return null; }
    }

    private static Phase phaseOf(PhaseType pt) {
        if (pt == null) return Phase.Phase_None;
        switch (pt) {
            case UNTAP: case UPKEEP: case DRAW: return Phase.Phase_Beginning;
            case MAIN1: return Phase.Phase_Main1;
            case MAIN2: return Phase.Phase_Main2;
            case END_OF_TURN: case CLEANUP: return Phase.Phase_Ending;
            default: return Phase.Phase_Combat;
        }
    }

    private static Step stepOf(PhaseType pt) {
        if (pt == null) return Step.Step_None;
        switch (pt) {
            case UNTAP: return Step.Step_Untap;
            case UPKEEP: return Step.Step_Upkeep;
            case DRAW: return Step.Step_Draw;
            case COMBAT_BEGIN: return Step.Step_BeginCombat;
            case COMBAT_DECLARE_ATTACKERS: return Step.Step_DeclareAttack;
            case COMBAT_DECLARE_BLOCKERS: return Step.Step_DeclareBlock;
            case COMBAT_FIRST_STRIKE_DAMAGE: return Step.Step_FirstStrikeDamage;
            case COMBAT_DAMAGE: return Step.Step_CombatDamage;
            case COMBAT_END: return Step.Step_EndCombat;
            case END_OF_TURN: return Step.Step_End;
            case CLEANUP: return Step.Step_Cleanup;
            default: return Step.Step_None;
        }
    }

    /** The step the client's "To ..." hint should name: Forge's next step, minus steps that will be skipped. */
    private PhaseType nextShownPhase(PhaseType pt) {
        PhaseType next = PhaseType.getNext(pt, false);
        forge.game.combat.Combat combat = game.getCombat();
        if (next == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE && !firstStrikeInCombat()) next = PhaseType.COMBAT_DAMAGE;
        if (pt == PhaseType.COMBAT_DECLARE_ATTACKERS && (combat == null || combat.getAttackers().isEmpty())) next = PhaseType.COMBAT_END;
        return next;
    }

    /** Does any combatant deal first-strike damage (first strike / double strike)? */
    private boolean firstStrikeInCombat() {
        forge.game.combat.Combat combat = game.getCombat();
        if (combat == null) return false;
        for (Card c : combat.getAttackers()) if (c.hasFirstStrike() || c.hasDoubleStrike()) return true;
        for (Card c : combat.getAllBlockers()) if (c.hasFirstStrike() || c.hasDoubleStrike()) return true;
        return false;
    }

    private TurnInfo turnInfo() {
        TurnInfo.Builder t = TurnInfo.newBuilder();
        forge.game.phase.PhaseHandler ph = game.getPhaseHandler();
        PhaseType pt = ph.getPhase();
        // Forge always begins the first-strike damage step (it just gives no priority without first strikers); MTGA
        // has no such step then, so keep showing declare blockers instead of flashing an extra step on the ladder.
        if (pt == PhaseType.COMBAT_FIRST_STRIKE_DAMAGE && !firstStrikeInCombat()) pt = PhaseType.COMBAT_DECLARE_BLOCKERS;
        Player active = ph.getPlayerTurn();
        Player prio = ph.getPriorityPlayer();
        t.setTurnNumber(Math.max(0, ph.getTurn()));
        t.setActivePlayer(active == null ? HUMAN_SEAT : seatOf(active));
        if (pt != null) {
            t.setPriorityPlayer(prio == null ? t.getActivePlayer() : seatOf(prio));
            t.setPhase(phaseOf(pt)).setStep(stepOf(pt));
            PhaseType next = nextShownPhase(pt);
            t.setNextPhase(phaseOf(next)).setNextStep(stepOf(next));
        }
        Player decider = decisionOverride != null ? decisionOverride : prio;
        t.setDecisionPlayer(decider == null ? t.getActivePlayer() : seatOf(decider));
        return t.build();
    }

    // ---- messages ----------------------------------------------------------------------------------------------

    public GameStateMessage.Builder full(GameInfoSupplier info) {
        Snapshot s = snapshot();
        GameStateMessage.Builder m = GameStateMessage.newBuilder().setType(GameStateType.GameStateType_Full)
                .setGameStateId(++gsId);
        info.fill(m.getGameInfoBuilder(), matchId);
        for (int seat : new int[] {HUMAN_SEAT, AI_SEAT}) {
            m.addTeams(TeamInfo.newBuilder().setId(seat).addPlayerIds(seat).setStatus(TeamStatus.TeamStatus_InGame));
        }
        m.addAllPlayers(s.players.values());
        m.setTurnInfo(s.turn);
        m.addAllZones(s.zones.values());
        m.addAllGameObjects(s.objs.values());
        sentActions = actionInfos(s);
        m.addAllActions(sentActions);
        for (Map.Entry<String, AnnotationInfo.Builder> e : s.persistent.entrySet()) {
            AnnotationInfo a = e.getValue().setId(++annId).build();
            sentPersistent.put(e.getKey(), a);
            m.addPersistentAnnotations(a);
        }
        clearHints();
        remember(s);
        return m;
    }

    private void clearHints() {
        pendingActions.clear();
        earlyAnns.clear();
        damagedCards.clear();
        lateAnns.clear();
        playerDamage.clear();
        lifeAffector.clear();
    }

    /** Diff versus the last message sent; null when nothing changed. */
    public GameStateMessage.Builder diff(boolean playStage) { return diff(playStage, true); }

    /**
     * Diff versus the last message sent; null when nothing changed. Unforced diffs (from Forge events) are skipped
     * while a card is between zones (Forge fires events mid-move); request-time diffs are always sent.
     */
    public GameStateMessage.Builder diff(boolean playStage, boolean force) {
        Snapshot s = snapshot();
        if (!force && inTransit(s) && ++unstable < 50) return null;
        unstable = 0;
        GameStateMessage.Builder m = GameStateMessage.newBuilder().setType(GameStateType.GameStateType_Diff);
        boolean changed = playStage || pendingResults != null || !earlyAnns.isEmpty() || !lateAnns.isEmpty() || !announceReveals.isEmpty();
        // The client replaces its GameInfo with whatever a diff carries (MatchManager reads MatchState, format, variant
        // from it), so a diff's GameInfo is always complete: a bare {stage=Play} reset MatchState to None, which hid
        // the Concede button for the whole game.
        // Game over: Forge has already removed the loser's cards by the time it reports the end; the client must keep
        // showing the final board (it stays visible behind VICTORY / "View Battlefield"), so no zone emptying or
        // object deletions go out with the result.
        boolean gameOver = pendingResults != null;
        if (pendingResults != null) {
            info.fill(m.getGameInfoBuilder(), matchId);
            m.getGameInfoBuilder().setStage(GameStage.GameStage_GameOver)
                    .setMatchState(pendingMatchState).addAllResults(pendingResults);
            pendingResults = null;
        } else if (playStage) {
            info.fill(m.getGameInfoBuilder(), matchId);
            m.getGameInfoBuilder().setStage(GameStage.GameStage_Play);
        }
        for (GameObjectInfo o : s.objs.values()) {
            if (gameOver) break;
            // the client refreshes a card's view only when its object comes in a diff: a door opening must resend the Room
            boolean doorChanged = s.doors.containsKey(o.getInstanceId()) && !s.doors.get(o.getInstanceId()).equals(sentDoors.get(o.getInstanceId()));
            if (doorChanged || !o.equals(sentObjs.get(o.getInstanceId()))) {
                m.addGameObjects(o);
                changed = true;
            }
        }
        List<Integer> deleted = new ArrayList<>();
        for (Integer id : sentObjs.keySet()) {
            if (gameOver) break;
            if (!s.objs.containsKey(id)) { m.addDiffDeletedInstanceIds(id); deleted.add(id); changed = true; }
        }
        for (ZoneInfo z : s.zones.values()) {
            if (gameOver) break;
            if (!z.equals(sentZones.get(z.getZoneId()))) { m.addZones(z); changed = true; }
        }
        for (PlayerInfo p : s.players.values()) {
            if (!p.equals(sentPlayers.get(p.getSystemSeatNumber()))) { m.addPlayers(p); changed = true; }
        }
        if (!s.turn.equals(sentTurn)) {
            m.setTurnInfo(s.turn);
            // Priority bouncing between players while Forge auto-passes is not worth a message of its own (every
            // step went out twice, prio 2 then 1, and the ladder blinked); it rides along with the next real change.
            if (force || sentTurn == null || !sameExceptPriority(s.turn, sentTurn)) changed = true;
        }
        List<com.wizards.mtga.gre.ActionInfo> acts = actionInfos(s);
        if (!acts.equals(sentActions)) changed = true;

        // persistent annotations (counters): changed/added ones are re-sent with the same id, gone ones deleted
        Map<String, AnnotationInfo> nowPersistent = new LinkedHashMap<>();
        List<AnnotationInfo> attached = new ArrayList<>();      // AttachmentCreated: the client animates the (re)attach
        for (Map.Entry<String, AnnotationInfo.Builder> e : s.persistent.entrySet()) {
            AnnotationInfo old = sentPersistent.get(e.getKey());
            AnnotationInfo cand = e.getValue().setId(old == null ? 0 : old.getId()).build();
            if (old != null && old.equals(cand)) { nowPersistent.put(e.getKey(), old); continue; }
            AnnotationInfo a = e.getValue().setId(old == null ? ++annId : old.getId()).build();
            nowPersistent.put(e.getKey(), a);
            m.addPersistentAnnotations(a);
            changed = true;
            if (e.getKey().startsWith("attach:")) {
                attached.add(ann(AnnotationType.AnnotationType_AttachmentCreated, a.getAffectorId(), a.getAffectedIds(0)).build());
            }
        }
        for (Map.Entry<String, AnnotationInfo> e : sentPersistent.entrySet()) {
            if (!nowPersistent.containsKey(e.getKey())) { m.addDiffDeletedPersistentAnnotationIds(e.getValue().getId()); changed = true; }
        }
        if (!changed) return null;
        sentPersistent.clear();
        sentPersistent.putAll(nowPersistent);

        if (Boolean.getBoolean("mtga.debugIds")) {
            for (IdChange ch : s.idChanges) {
                if (!sentObjs.containsKey(ch.oldId())) System.out.println("[mtga] DEBUG id change from unsent id " + ch.oldId() + " -> " + ch.newId()
                        + " card " + ch.card() + " zones " + ch.srcZone() + "->" + ch.dstZone() + " gs " + (gsId + 1) + " everSent=" + everSent.contains(ch.oldId()));
            }
        }
        for (AnnotationInfo a : annotations(s, deleted)) m.addAnnotations(a);
        m.addAllAnnotations(attached);
        m.addAllActions(acts);       // a diff replaces the client's whole list
        sentActions = acts;
        clearHints();
        m.setGameStateId(++gsId).setPrevGameStateId(gsId - 1);
        remember(s);
        return m;
    }

    private boolean inTransit(Snapshot s) {
        for (GameObjectInfo o : sentObjs.values()) {
            if (o.getType() == GameObjectType.GameObjectType_Ability || s.objs.containsKey(o.getInstanceId())) continue;
            Integer f = forgeByIid.get(o.getInstanceId());
            if (f != null && !s.forgeIds.contains(f) && !sentTokens.contains(f)) return true;
        }
        return false;
    }

    /** Transient annotations for this diff, in the order the client should animate them. */
    private List<AnnotationInfo> annotations(Snapshot s, List<Integer> deleted) {
        List<AnnotationInfo> out = new ArrayList<>();
        TurnInfo t = s.turn;
        if (sentTurn != null) {
            boolean newTurn = t.getTurnNumber() != sentTurn.getTurnNumber() && t.getTurnNumber() > 0;
            if (newTurn) {
                out.add(ann(AnnotationType.AnnotationType_NewTurnStarted, t.getActivePlayer(), t.getActivePlayer()).build());
            }
            if (newTurn || t.getPhase() != sentTurn.getPhase() || t.getStep() != sentTurn.getStep()) {
                if (t.getPhase() != Phase.Phase_None) {
                    out.add(ann(AnnotationType.AnnotationType_PhaseOrStepModified, t.getActivePlayer(), t.getActivePlayer())
                            .addDetails(intDetail("phase", t.getPhase().getNumber()))
                            .addDetails(intDetail("step", t.getStep().getNumber())).build());
                }
            }
        }
        out.addAll(earlyAnns);
        if (!announceReveals.isEmpty()) {
            Map<Integer, List<Integer>> bySource = new LinkedHashMap<>();
            for (Revealed r : revealedCards) if (announceReveals.contains(r.iid())) bySource.computeIfAbsent(r.sourceZone(), k -> new ArrayList<>()).add(r.iid());
            for (Map.Entry<Integer, List<Integer>> e : bySource.entrySet()) {
                AnnotationInfo.Builder a = ann(AnnotationType.AnnotationType_CardRevealed, revealSourceSeat, e.getValue().get(0));
                for (int i = 1; i < e.getValue().size(); i++) a.addAffectedIds(e.getValue().get(i));
                out.add(a.addDetails(intDetail("source_zone", e.getKey())).build());
            }
            announceReveals.clear();
        }
        // a revealed card that stays face-up in the opponent's hand: the client creates the reveal from this annotation (its generator for
        // new RevealedCard objects only serves undo); the object it names is the one with the hand as its zone
        createdReveals.retainAll(s.objs.keySet());
        for (GameObjectInfo o : s.objs.values()) {
            if (o.getType() != GameObjectType.GameObjectType_RevealedCard || (o.getZoneId() != 42 && o.getZoneId() != 32)) continue;
            if (createdReveals.add(o.getInstanceId())) {
                out.add(ann(AnnotationType.AnnotationType_RevealedCardCreated, o.getInstanceId(), o.getInstanceId()).build());
            }
        }
        Map<Long, Integer> animated = new HashMap<>();
        for (IdChange ch : s.idChanges) {
            GameObjectInfo now = s.objs.get(ch.newId());
            // a mass move out of a library or graveyard (a library exiled or milled, a graveyard exiled): the client plays one animation
            // per card, so only the first few of each source/destination pair are announced. The rest change zone without a transfer
            // (the state is still right; the client just shows them in their new zone).
            boolean fromPile = ch.srcZone() == 31 || ch.srcZone() == 41 || ch.srcZone() == 33 || ch.srcZone() == 43;
            long pair = ((long) ch.srcZone() << 32) | (ch.dstZone() & 0xffffffffL);
            if (fromPile && animated.merge(pair, 1, Integer::sum) > MASS_MOVE_ANIMATED) {
                categoryHint.remove(ch.card().getId());
                continue;
            }
            out.add(ann(AnnotationType.AnnotationType_ObjectIdChanged, 0, ch.oldId())
                    .addDetails(intDetail("orig_id", ch.oldId())).addDetails(intDetail("new_id", ch.newId())).build());
            String cat = categoryHint.remove(ch.card().getId());
            // stack hints (Resolve/Countered) are only about leaving the stack; a stale one must not label a later
            // move of the same card (a ninjutsu return showed up as "Countered")
            if (("Resolve".equals(cat) || "Countered".equals(cat)) && ch.srcZone() != Z_STACK) cat = null;
            if (cat == null) cat = categoryOf(ch.srcZone(), ch.dstZone(), now);
            if ("Destroy".equals(cat) && damagedCards.contains(ch.card().getId())) cat = ch.card().isPlaneswalker() ? "ZeroLoyalty" : "Damage";
            int affector = now == null ? 0 : now.getControllerSeatId();
            out.add(ann(AnnotationType.AnnotationType_ZoneTransfer, affector, ch.newId())
                    .addDetails(intDetail("zone_src", ch.srcZone())).addDetails(intDetail("zone_dest", ch.dstZone()))
                    .addDetails(KeyValuePairInfo.newBuilder().setKey("category").setType(KeyValuePairValueType.KeyValuePairValueType_string).addValueString(cat))
                    .build());
        }
        for (int tok : s.createdTokens) out.add(ann(AnnotationType.AnnotationType_TokenCreated, 0, tok).build());
        for (Map.Entry<Integer, Integer> e : s.createdAbilities.entrySet()) {
            out.add(ann(AnnotationType.AnnotationType_AbilityInstanceCreated, e.getValue(), e.getKey()).build());
        }
        for (PendingAction pa : pendingActions) {
            String key = pa.cardId() >= 0 ? "c" + pa.cardId() : "s" + pa.stackId();
            int[] b = s.binds.get(key);
            Integer id = b != null ? Integer.valueOf(b[0]) : iidByKey.get(key);
            if (id == null || !s.objs.containsKey(id)) continue;
            out.add(ann(AnnotationType.AnnotationType_UserActionTaken, pa.seat(), id)
                    .addDetails(intDetail("actionType", pa.type().getNumber())).addDetails(intDetail("abilityGrpId", pa.abilityGrp())).build());
        }
        pendingActions.clear();
        for (GameObjectInfo o : s.objs.values()) {
            GameObjectInfo old = sentObjs.get(o.getInstanceId());
            if (old == null || o.getZoneId() != Z_BF || old.getZoneId() != Z_BF) continue;
            if (old.getIsTapped() != o.getIsTapped()) {
                out.add(ann(AnnotationType.AnnotationType_TappedUntappedPermanent, o.getInstanceId(), o.getInstanceId())
                        .addDetails(intDetail("tapped", o.getIsTapped() ? 1 : 0)).build());
            }
        }
        for (Map.Entry<Integer, Map<Integer, Integer>> e : s.counters.entrySet()) {
            Map<Integer, Integer> before = sentCounters.getOrDefault(e.getKey(), Map.of());
            if (!sentObjs.containsKey(e.getKey()) && !isSeat(e.getKey())) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) counterDelta(out, e.getKey(), c.getKey(), c.getValue() - before.getOrDefault(c.getKey(), 0));
            for (Map.Entry<Integer, Integer> c : before.entrySet()) if (!e.getValue().containsKey(c.getKey())) counterDelta(out, e.getKey(), c.getKey(), -c.getValue());
        }
        for (Map.Entry<Integer, Map<Integer, Integer>> e : sentCounters.entrySet()) {
            if (s.counters.containsKey(e.getKey())) continue;
            // a planeswalker that died to damage: the client folds its ZeroLoyalty move into the combat frame only after a loyalty removal on the old id
            boolean diedPw = !s.objs.containsKey(e.getKey()) && isOldIdOfMove(s, e.getKey());
            if (!s.objs.containsKey(e.getKey()) && !isSeat(e.getKey()) && !diedPw) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) {
                if (diedPw && c.getKey() != com.wizards.mtga.gre.CounterType.CounterType_Loyalty_VALUE) continue;
                counterDelta(out, e.getKey(), c.getKey(), -c.getValue());
            }
        }
        for (PlayerInfo p : s.players.values()) {
            PlayerInfo old = sentPlayers.get(p.getSystemSeatNumber());
            int seat = p.getSystemSeatNumber();
            List<AnnotationInfo> dmg = playerDamage.remove(seat);
            if (old == null || old.getLifeTotal() == p.getLifeTotal()) continue;
            if (dmg != null) out.addAll(dmg);
            int affector = lifeAffector.getOrDefault(seat, 0);
            out.add(ann(AnnotationType.AnnotationType_ModifiedLife, affector, seat)
                    .addDetails(intDetail("life", p.getLifeTotal() - old.getLifeTotal())).build());
        }
        out.addAll(lateAnns);
        for (int id : deleted) {
            GameObjectInfo old = sentObjs.get(id);
            if (old == null) continue;
            if (old.getType() == GameObjectType.GameObjectType_Ability) {
                out.add(ann(AnnotationType.AnnotationType_AbilityInstanceDeleted, old.getParentId(), id).build());
            } else if (!isOldIdOfMove(s, id) && sentTokens.contains(forgeByIid.getOrDefault(id, -1))) {
                out.add(ann(AnnotationType.AnnotationType_TokenDeleted, 0, id).build());
            }
        }
        return out;
    }

    /** Cards of one zone-to-zone move in a single diff that still get a ZoneTransfer animation (-Dmtga.massmove=n). */
    public static final int MASS_MOVE_ANIMATED = Integer.getInteger("mtga.massmove", 6);

    private static boolean isOldIdOfMove(Snapshot s, int id) {
        for (IdChange ch : s.idChanges) if (ch.oldId() == id) return true;
        return false;
    }

    private static boolean isSeat(int id) { return id == HUMAN_SEAT || id == AI_SEAT; }

    private void counterDelta(List<AnnotationInfo> out, int iid, int type, int delta) {
        if (delta == 0) return;
        AnnotationType t = delta > 0 ? AnnotationType.AnnotationType_CounterAdded : AnnotationType.AnnotationType_CounterRemoved;
        out.add(ann(t, iid, iid).addDetails(intDetail("counter_type", type)).addDetails(intDetail("transaction_amount", Math.abs(delta))).build());
    }

    private Map<Integer, Map<Integer, Integer>> sentCounters = new HashMap<>();

    private final Set<Integer> everSent = new HashSet<>();

    private void remember(Snapshot s) {
        everSent.addAll(s.objs.keySet());
        // annotations() reads sentTokens of the previous state, so commit after it ran (remember is called last)
        commit(s);
        sentObjs = new HashMap<>(s.objs);
        sentDoors = new HashMap<>(s.doors);
        sentZones = new HashMap<>(s.zones);
        sentTurn = s.turn;
        sentPlayers.clear();
        sentPlayers.putAll(s.players);
        sentCounters = new HashMap<>(s.counters);
    }

    private AnnotationInfo.Builder ann(AnnotationType type, int affector, int affected) {
        return AnnotationInfo.newBuilder().setId(++annId).setAffectorId(affector).addAffectedIds(affected).addType(type);
    }

    /** A LinkInfo "Choose" annotation on a card: the client turns it into blue text in the card's text box (ChooseLinkType Parity / Color). */
    private static AnnotationInfo.Builder linkChoose(int iid, String kind, int value) {
        return AnnotationInfo.newBuilder().setAffectorId(iid).addAffectedIds(value)
                .addType(AnnotationType.AnnotationType_LinkInfo)
                .addDetails(intDetail("LinkType", com.wizards.mtga.gre.LinkType.LinkType_Choose_VALUE))
                .addDetails(intDetail("sourceAbilityGRPID", 0))
                .addDetails(KeyValuePairInfo.newBuilder().setKey("ChooseLinkType").setType(KeyValuePairValueType.KeyValuePairValueType_string).addValueString(kind));
    }

    private static KeyValuePairInfo.Builder intDetail(String key, int v) {
        return KeyValuePairInfo.newBuilder().setKey(key).setType(KeyValuePairValueType.KeyValuePairValueType_int32).addValueInt32(v);
    }

    private static String categoryOf(int src, int dst, GameObjectInfo o) {
        boolean fromLib = src == 31 || src == 41, fromHand = src == 32 || src == 42, fromGy = src == 33 || src == 43;
        boolean toGy = dst == 33 || dst == 43, toHand = dst == 32 || dst == 42, toLib = dst == 31 || dst == 41;
        boolean land = o != null && o.getCardTypesList().contains(CardType.CardType_Land);
        if (fromLib && toHand) return "Draw";
        if (fromHand && dst == Z_BF) return land ? "PlayLand" : "Put";
        if (fromHand && dst == Z_STACK) return "CastSpell";
        if (src == Z_STACK && (dst == Z_BF || toGy || dst == Z_EXILE)) return "Resolve";
        if (src == Z_BF && toGy) return "Destroy";
        if (fromHand && toGy) return "Discard";
        if (fromLib && toGy) return "Mill";
        if (dst == Z_EXILE) return "Exile";
        if (toHand || toLib) return "Return";
        if (fromGy && dst == Z_BF) return "Return";
        return "Put";
    }

    private static boolean sameExceptPriority(TurnInfo a, TurnInfo b) {
        return a.toBuilder().clearPriorityPlayer().clearDecisionPlayer().build()
                .equals(b.toBuilder().clearPriorityPlayer().clearDecisionPlayer().build());
    }

    /** True while combat damage is being dealt (damage events are then batched into one diff = one combat frame). */
    public boolean dealingCombatDamage() { return inCombatDamage(); }

    /**
     * True for events whose diff should wait for the next one: everything of a combat damage step except the step change
     * itself (damage, deaths, life, counters go out together), and zone moves into the graveyard or exile, or out of the
     * graveyard, while an effect resolves (the whole graveyard leaving is one diff).
     */
    private boolean heldCombat, hideStack;

    /**
     * Diff of the held combat damage without the stack, for a trigger that goes on the stack right after it (Ragavan): the
     * damage frame plays first, the trigger follows in the next diff. Null when no combat damage is waiting.
     */
    public GameStateMessage.Builder diffHeldCombatWithoutStack() {
        if (!heldCombat) return null;
        heldCombat = false;
        hideStack = true;
        try { return diff(false, false); } finally { hideStack = false; }
    }

    public boolean holdForBatch(GameEvent ev) {
        if (ev instanceof forge.game.event.GameEventTurnPhase || ev instanceof forge.game.event.GameEventTurnBegan
                || ev instanceof forge.game.event.GameEventGameFinished || ev instanceof forge.game.event.GameEventGameOutcome) return false;
        if (inCombatDamage()) { heldCombat = true; return true; }
        if (ev instanceof forge.game.event.GameEventZone z && game.getStack().isResolving()) {
            // the per-card zone notification that follows each move: it would flush the held diff after every card of a mill / exile
            ZoneType zt = z.zoneType();
            return zt == ZoneType.Library || zt == ZoneType.Graveyard || zt == ZoneType.Exile;
        }
        if (ev instanceof forge.game.event.GameEventCardChangeZone e && game.getStack().isResolving()) {
            ZoneType from = e.from() == null ? null : e.from().zoneType(), to = e.to() == null ? null : e.to().zoneType();
            return from == ZoneType.Graveyard || to == ZoneType.Exile || to == ZoneType.Graveyard && from != ZoneType.Stack;
        }
        return false;
    }

    /** Fills the static GameInfo block. */
    public interface GameInfoSupplier {
        void fill(com.wizards.mtga.gre.GameInfo.Builder gi, String matchId);
    }

    public static final GameInfoSupplier DEFAULT_INFO = (gi, matchId) -> gi
            .setMatchID(matchId).setGameNumber(1)
            .setStage(GameStage.GameStage_Start)
            .setType(com.wizards.mtga.gre.GameType.GameType_Duel)
            .setVariant(com.wizards.mtga.gre.GameVariant.GameVariant_Normal)
            .setMatchState(com.wizards.mtga.gre.MatchState.MatchState_GameInProgress)
            .setMatchWinCondition(com.wizards.mtga.gre.MatchWinCondition.MatchWinCondition_SingleElimination)
            .setMaxTimeoutCount(3).setTimeoutDurationSec(75)
            .setSuperFormat(com.wizards.mtga.gre.SuperFormat.SuperFormat_Constructed)
            .setMulliganType(com.wizards.mtga.gre.MulliganType.MulliganType_London)
            .setFreeMulliganCount(0);

    /** GameInfo of game {@code gameNumber} in a match won by {@code gamesToWin} games (1 = a single game); {@code minDeck} bounds the sideboarded main deck. */
    public static GameInfoSupplier matchInfo(int gameNumber, int gamesToWin, int minDeck) { return matchInfo(gameNumber, gamesToWin, minDeck, false); }

    /** {@code brawl}: a game with commanders (the client checks every player's deck has one, CommanderDeckValidator). */
    public static GameInfoSupplier matchInfo(int gameNumber, int gamesToWin, int minDeck, boolean brawl) {
        if (gamesToWin <= 1 && gameNumber == 1 && !brawl) return DEFAULT_INFO;
        return (gi, matchId) -> {
            DEFAULT_INFO.fill(gi, matchId);
            if (brawl) gi.setVariant(com.wizards.mtga.gre.GameVariant.GameVariant_Brawl).setFreeMulliganCount(1);       // the first mulligan is free
            if (gamesToWin <= 1 && gameNumber == 1) return;
            gi.setGameNumber(gameNumber).setMatchWinCondition(gamesToWin > 1
                    ? com.wizards.mtga.gre.MatchWinCondition.MatchWinCondition_Best2of3 : com.wizards.mtga.gre.MatchWinCondition.MatchWinCondition_SingleElimination);
            // the client's sideboard screen validates the deck it submits against these (all zero = "deck too big")
            gi.getDeckConstraintInfoBuilder().setMinDeckSize(minDeck).setMaxDeckSize(250).setMinSideboardSize(0).setMaxSideboardSize(15);
        };
    }

}

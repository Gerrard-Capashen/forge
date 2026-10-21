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
import forge.game.card.CardView;
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
    private Map<Integer, ZoneInfo> sentZones = new HashMap<>();
    private TurnInfo sentTurn;
    private final Map<Integer, PlayerInfo> sentPlayers = new HashMap<>();
    private final Map<String, AnnotationInfo> sentPersistent = new LinkedHashMap<>();
    private List<com.wizards.mtga.gre.ResultSpec> pendingResults;

    // ---- instance ids: key "c<forge card id>" (cards) or "s<stack instance id>" (abilities on the stack)
    private final Map<String, Integer> iidByKey = new HashMap<>();
    private final Map<String, Integer> zoneByKey = new HashMap<>();
    private final Map<Integer, Integer> forgeByIid = new HashMap<>();
    private final Map<Integer, Integer> stackByIid = new HashMap<>();
    private final Set<Integer> usedIids = new HashSet<>();
    private int nextIid = 1000;

    // ---- hints from Forge events, consumed by the next diff
    private final List<AnnotationInfo> earlyAnns = new ArrayList<>();
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
    public void setResults(List<com.wizards.mtga.gre.ResultSpec> results) { pendingResults = results; }

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
            CardView host = e.sa() == null ? null : e.sa().getHostCard();
            if (host != null) categoryHint.put(host.getId(), "Countered");
        } else if (ev instanceof GameEventCardSacrificed e) {
            if (e.card() != null) categoryHint.put(e.card().getId(), "Sacrifice");
        } else if (ev instanceof GameEventCardDamaged e) {
            if (e.card() == null) return;
            Integer victim = iidByKey.get("c" + e.card().getId());
            if (victim == null) return;
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
        if (c.isToken()) {
            return tokenGrp.computeIfAbsent(c.getId(), k -> {
                SpellAbility src = c.getTokenSpawningAbility();
                int srcGrp = src == null || src.getHostCard() == null ? 0 : grpIdOf(src.getHostCard());
                int t = db.tokenGrpId(c.getName(), srcGrp, c.getNetPower(), c.getNetToughness());
                return t != 0 ? t : db.grpId(c.getName());      // token copies of real cards
            });
        }
        Integer g = grpByName.get(c.getName());
        if (g == null) {
            g = db.grpId(c.getName());
            grpByName.put(c.getName(), g);
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
            for (PoolMana pm : pool(p)) {
                Card src = pm.mana().getSourceCard();
                pi.addManaPool(com.wizards.mtga.gre.ManaInfo.newBuilder().setManaId(pm.id()).setColor(manaColor(pm.mana().getColor())).setCount(1)
                        .setSrcInstanceId(src == null ? 0 : idInOrZero(src)));
            }
            s.players.put(seat, pi.build());
        }
        addZone(s, ZoneType.Battlefield, 0, game.getCardsIn(ZoneType.Battlefield));
        addStack(s);
        addZone(s, ZoneType.Exile, 0, game.getCardsIn(ZoneType.Exile));
        addZone(s, ZoneType.Command, 0, game.getCardsIn(ZoneType.Command));
        addExiledUnder(s);
        addAttachments(s);
        for (Map.Entry<String, AnnotationInfo.Builder> e : extraPersistent.entrySet()) s.persistent.put(e.getKey(), e.getValue());
        s.zones.put(Z_LIMBO, ZoneInfo.newBuilder().setZoneId(Z_LIMBO).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Limbo)
                .setVisibility(Visibility.Visibility_Public).build());
        s.zones.put(Z_REVEALED, ZoneInfo.newBuilder().setZoneId(Z_REVEALED).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Revealed)
                .setVisibility(Visibility.Visibility_Public).setOwnerSeatId(HUMAN_SEAT).build());
        // zones the client looks up unconditionally (LocalSideboard in library searches, OpponentRevealed, PhasedOut)
        s.zones.put(Z_REVEALED_OPP, ZoneInfo.newBuilder().setZoneId(Z_REVEALED_OPP).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Revealed)
                .setVisibility(Visibility.Visibility_Public).setOwnerSeatId(AI_SEAT).build());
        s.zones.put(Z_PHASED_OUT, ZoneInfo.newBuilder().setZoneId(Z_PHASED_OUT).setType(com.wizards.mtga.gre.ZoneType.ZoneType_PhasedOut)
                .setVisibility(Visibility.Visibility_Public).build());
        s.zones.put(Z_SIDEBOARD, ZoneInfo.newBuilder().setZoneId(Z_SIDEBOARD).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Sideboard)
                .setVisibility(Visibility.Visibility_Private).setOwnerSeatId(HUMAN_SEAT).addViewers(HUMAN_SEAT).build());
        s.zones.put(Z_SIDEBOARD_OPP, ZoneInfo.newBuilder().setZoneId(Z_SIDEBOARD_OPP).setType(com.wizards.mtga.gre.ZoneType.ZoneType_Sideboard)
                .setVisibility(Visibility.Visibility_Private).setOwnerSeatId(AI_SEAT).addViewers(AI_SEAT).build());
        s.turn = turnInfo();
        return s;
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

    private void addZone(Snapshot s, ZoneType t, int seat, Iterable<Card> cards) {
        int zid = zoneId(t, seat);
        ZoneInfo.Builder z = zoneHeader(t, seat, zid);
        boolean hidden = (t == ZoneType.Library) || (t == ZoneType.Hand && seat != HUMAN_SEAT);
        for (Card c : cards) {
            if (c.isImmutable()) continue;       // Forge effect/emblem cards have no client representation
            int iid = track(s, c, zid);
            z.addObjectInstanceIds(iid);
            s.objs.put(iid, objectInfo(s, c, iid, zid, t, hidden && !revealed.contains(c.getId())));
        }
        s.zones.put(zid, z.build());
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
                continue;
            }
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
        }
        for (Card c : game.getCardsIn(ZoneType.Stack)) {      // spell copies etc. not matched to a stack item
            if (!stackCards.contains(c.getId())) continue;
            int iid = track(s, c, Z_STACK);
            z.addObjectInstanceIds(iid);
            s.objs.put(iid, objectInfo(s, c, iid, Z_STACK, ZoneType.Stack, false));
        }
        s.zones.put(Z_STACK, z.build());
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
            if (c.isToken() && zid == Z_BF && gsId > 0) s.createdTokens.add(id);
            return id;
        }
        int was = zoneByKey.getOrDefault(key, -1);
        if (was == -1) {
            s.binds.put(key, new int[] {cur, zid});
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
        int title = db.titleId(grp);
        if (title != 0) o.setName(title);
        // the client builds a card's abilities (rules text, activation buttons, loyalty) only from uniqueAbilities
        Set<Integer> printed = new HashSet<>();
        List<Integer> abilityIds = new ArrayList<>();
        for (CardDb.Ability a : db.abilities(grp)) abilityIds.add(a.id());
        if (abilityIds.isEmpty() && c.isLand()) abilityIds.addAll(AbilityMap.basicLandAbilities(c));      // intrinsic: not in the card DB
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
        boolean priv = t == ZoneType.Hand || t == ZoneType.Library;
        o.setVisibility(priv ? Visibility.Visibility_Private : Visibility.Visibility_Public);
        if (t == ZoneType.Hand) o.addViewers(owner);
        else if (t == ZoneType.Library) o.addViewers(HUMAN_SEAT);
        for (forge.card.CardType.CoreType ct : c.getType().getCoreTypes()) {
            CardType m = enumOrNull(CardType.class, "CardType_" + ct.name());
            if (m != null) o.addCardTypes(m);
        }
        for (String st : c.getType().getSubtypes()) {
            SubType m = enumOrNull(SubType.class, "SubType_" + st.replace(" ", "").replace("'", "").replace("-", ""));
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
        if (c.isPlaneswalker()) o.setLoyalty(UInt32Value.newBuilder().setValue(Math.max(0, c.getCurrentLoyalty())));
        if (t == ZoneType.Battlefield) {
            if (c.isCreature() || c.isPlaneswalker()) o.setDamage(c.getDamage());
            o.setIsTapped(c.isTapped());
            o.setHasSummoningSickness(c.isCreature() && c.isSick());
            if (game.getCombat() != null && c.isAttacking()) {
                o.setAttackState(com.wizards.mtga.gre.AttackState.AttackState_Attacking);
                forge.game.GameEntity def = game.getCombat().getDefenderByAttacker(c);
                int target = def instanceof Player dp ? seatOf(dp) : def instanceof Card dc ? idIn(s, dc) : seatOf(c.getController()) == HUMAN_SEAT ? AI_SEAT : HUMAN_SEAT;
                o.getAttackInfoBuilder().setTargetId(target);
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
        }
        return o.build();
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
        boolean changed = playStage || pendingResults != null || !earlyAnns.isEmpty() || !lateAnns.isEmpty();
        // The client replaces its GameInfo with whatever a diff carries (MatchManager reads MatchState, format, variant
        // from it), so a diff's GameInfo is always complete: a bare {stage=Play} reset MatchState to None, which hid
        // the Concede button for the whole game.
        // Game over: Forge has already removed the loser's cards by the time it reports the end; the client must keep
        // showing the final board (it stays visible behind VICTORY / "View Battlefield"), so no zone emptying or
        // object deletions go out with the result.
        boolean gameOver = pendingResults != null;
        if (pendingResults != null) {
            DEFAULT_INFO.fill(m.getGameInfoBuilder(), matchId);
            m.getGameInfoBuilder().setStage(GameStage.GameStage_GameOver)
                    .setMatchState(com.wizards.mtga.gre.MatchState.MatchState_MatchComplete).addAllResults(pendingResults);
            pendingResults = null;
        } else if (playStage) {
            DEFAULT_INFO.fill(m.getGameInfoBuilder(), matchId);
            m.getGameInfoBuilder().setStage(GameStage.GameStage_Play);
        }
        for (GameObjectInfo o : s.objs.values()) {
            if (gameOver) break;
            if (!o.equals(sentObjs.get(o.getInstanceId()))) {
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
        for (IdChange ch : s.idChanges) {
            GameObjectInfo now = s.objs.get(ch.newId());
            out.add(ann(AnnotationType.AnnotationType_ObjectIdChanged, 0, ch.oldId())
                    .addDetails(intDetail("orig_id", ch.oldId())).addDetails(intDetail("new_id", ch.newId())).build());
            String cat = categoryHint.remove(ch.card().getId());
            // stack hints (Resolve/Countered) are only about leaving the stack; a stale one must not label a later
            // move of the same card (a ninjutsu return showed up as "Countered")
            if (("Resolve".equals(cat) || "Countered".equals(cat)) && ch.srcZone() != Z_STACK) cat = null;
            if (cat == null) cat = categoryOf(ch.srcZone(), ch.dstZone(), now);
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
            if (!sentObjs.containsKey(e.getKey())) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) counterDelta(out, e.getKey(), c.getKey(), c.getValue() - before.getOrDefault(c.getKey(), 0));
            for (Map.Entry<Integer, Integer> c : before.entrySet()) if (!e.getValue().containsKey(c.getKey())) counterDelta(out, e.getKey(), c.getKey(), -c.getValue());
        }
        for (Map.Entry<Integer, Map<Integer, Integer>> e : sentCounters.entrySet()) {
            if (s.counters.containsKey(e.getKey()) || !s.objs.containsKey(e.getKey())) continue;
            for (Map.Entry<Integer, Integer> c : e.getValue().entrySet()) counterDelta(out, e.getKey(), c.getKey(), -c.getValue());
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

    private static boolean isOldIdOfMove(Snapshot s, int id) {
        for (IdChange ch : s.idChanges) if (ch.oldId() == id) return true;
        return false;
    }

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
        sentZones = new HashMap<>(s.zones);
        sentTurn = s.turn;
        sentPlayers.clear();
        sentPlayers.putAll(s.players);
        sentCounters = new HashMap<>(s.counters);
    }

    private AnnotationInfo.Builder ann(AnnotationType type, int affector, int affected) {
        return AnnotationInfo.newBuilder().setId(++annId).setAffectorId(affector).addAffectedIds(affected).addType(type);
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

}

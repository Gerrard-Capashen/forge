package forge.mtga.forge;

import com.wizards.mtga.gre.AutoPassOption;
import com.wizards.mtga.gre.ManaSelectionType;
import com.wizards.mtga.gre.SettingScope;
import com.wizards.mtga.gre.SettingStatus;
import com.wizards.mtga.gre.SettingsMessage;
import com.wizards.mtga.gre.Stop;
import com.wizards.mtga.gre.StopType;
import forge.game.phase.PhaseType;

import java.util.EnumSet;
import java.util.Set;

/**
 * The client's game settings (stops, auto-pass, mana selection), merged from ConnectReq.defaultSettings and each
 * SetSettingsReq. "Full control" = AutoPassOption_FullControl: stop at every priority and pay mana by hand.
 */
public final class ClientSettings {
    private final Set<StopType> myStops = EnumSet.of(StopType.StopType_PrecombatMainPhase, StopType.StopType_PostcombatMainPhase);
    private final Set<StopType> opponentStops = EnumSet.noneOf(StopType.class);
    /** One-shot stops from a click on the phase ladder (SettingsMessage.transientStops): each is dropped once its step is reached. */
    private final Set<StopType> myTransient = EnumSet.noneOf(StopType.class);
    private final Set<StopType> opponentTransient = EnumSet.noneOf(StopType.class);
    private int transientTurn;           // the turn and step where a transient stop was last taken: it holds for that whole step
    private PhaseType transientPhase;
    private boolean transientSpent;
    private AutoPassOption autoPass = AutoPassOption.AutoPassOption_None;          // this turn's option (None/Clear = unset)
    private AutoPassOption defaultAutoPass = AutoPassOption.AutoPassOption_None;
    private AutoPassOption stackAutoPass = AutoPassOption.AutoPassOption_None;
    private ManaSelectionType manaSelection = ManaSelectionType.ManaSelectionType_Auto;
    private int autoPassTurnNumber;      // the turn a SetSettingsReq's autoPassOption was set for (0 = no limit)
    private java.util.function.IntSupplier currentTurn = () -> 0;

    public synchronized void setTurnSupplier(java.util.function.IntSupplier turn) { currentTurn = turn; }

    public synchronized void merge(SettingsMessage s) { merge(s, 0); }

    /**
     * turnNumber: SetSettingsReq.turnNumber. The pass-turn button (UnlessOpponentAction / Turn) is sent with the turn
     * it was pressed in and only lasts for that turn: pressed on the opponent's turn it must stop again on mine (it
     * kept passing through my whole turn 6 until the client reset it at Main 2).
     */
    public synchronized void merge(SettingsMessage s, int turnNumber) {
        if (s.getAutoPassOption() != AutoPassOption.AutoPassOption_None) autoPassTurnNumber = turnNumber;
        if (s.getClearAllStops() == SettingStatus.SettingStatus_Set) {
            myStops.clear();
            opponentStops.clear();
            myTransient.clear();
            opponentTransient.clear();
        }
        applyStops(s.getStopsList(), myStops, opponentStops);
        applyStops(s.getTransientStopsList(), myTransient, opponentTransient);
        if (s.getAutoPassOption() != AutoPassOption.AutoPassOption_None) autoPass = s.getAutoPassOption();
        if (s.getDefaultAutoPassOption() != AutoPassOption.AutoPassOption_None) defaultAutoPass = s.getDefaultAutoPassOption();
        if (s.getStackAutoPassOption() != AutoPassOption.AutoPassOption_None) stackAutoPass = s.getStackAutoPassOption();
        if (s.getManaSelectionType() != ManaSelectionType.ManaSelectionType_None) manaSelection = s.getManaSelectionType();
    }

    private static void applyStops(java.util.List<Stop> stops, Set<StopType> mine, Set<StopType> theirs) {
        for (Stop st : stops) {
            boolean opp = st.getAppliesTo() == SettingScope.SettingScope_Opponents, any = st.getAppliesTo() == SettingScope.SettingScope_AnyPlayer;
            for (Set<StopType> target : any ? java.util.List.of(mine, theirs) : java.util.List.of(opp ? theirs : mine)) {
                if (st.getStatus() == SettingStatus.SettingStatus_Set) target.add(st.getStopType());
                else if (st.getStatus() == SettingStatus.SettingStatus_Clear) target.remove(st.getStopType());
            }
        }
    }

    private AutoPassOption turnOption() {
        if (autoPassTurnNumber > 0 && currentTurn.getAsInt() > autoPassTurnNumber) {      // that turn is over
            autoPass = AutoPassOption.AutoPassOption_None;
            autoPassTurnNumber = 0;
        }
        boolean set = autoPass != AutoPassOption.AutoPassOption_None && autoPass != AutoPassOption.AutoPassOption_Clear;
        return set ? autoPass : defaultAutoPass;
    }

    public synchronized boolean fullControl() { return turnOption() == AutoPassOption.AutoPassOption_FullControl; }

    /** Auto-pass the whole turn (unless the opponent does something, for UnlessOpponentAction). */
    public synchronized boolean autoPassTurn() { return turnOption() == AutoPassOption.AutoPassOption_Turn; }

    public synchronized boolean autoPassUnlessOpponentAction() { return turnOption() == AutoPassOption.AutoPassOption_UnlessOpponentAction; }

    /** The "auto-resolve" toggle: never stop to respond to anything on the stack (spells and abilities) (StackAutoPassOption_ResolveAll). */
    public synchronized boolean autoResolveStack() { return stackAutoPass == AutoPassOption.AutoPassOption_ResolveAll; }

    public synchronized boolean manualMana() { return manaSelection == ManaSelectionType.ManaSelectionType_Manual || fullControl(); }

    /** Is there a stop on this phase/step for a turn owned by me (or the opponent)? */
    public synchronized boolean stopAt(PhaseType phase, boolean myTurn) {
        StopType t = stopOf(phase);
        return t != null && (myTurn ? myStops : opponentStops).contains(t);
    }

    /**
     * Is there a transient stop at this step of this turn? It holds for the whole step it was reached in (the player
     * may act and get priority back there) and is then spent; {@link #transientSpent()} says the client must be told.
     */
    public synchronized boolean transientStopAt(PhaseType phase, boolean myTurn, int turn) {
        if (turn == transientTurn && phase == transientPhase) return true;
        StopType t = stopOf(phase);
        if (t == null || !(myTurn ? myTransient : opponentTransient).remove(t)) return false;
        transientTurn = turn;
        transientPhase = phase;
        transientSpent = true;
        return true;
    }

    /** A transient stop was spent since the last call (the client's phase ladder still shows it). */
    public synchronized boolean transientSpent() {
        boolean was = transientSpent;
        transientSpent = false;
        return was;
    }

    public synchronized SettingsMessage toMessage() {
        SettingsMessage.Builder b = SettingsMessage.newBuilder().setAutoPassOption(autoPass).setDefaultAutoPassOption(defaultAutoPass)
                .setStackAutoPassOption(stackAutoPass).setManaSelectionType(manaSelection);
        for (StopType t : myStops) b.addStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Team).setStatus(SettingStatus.SettingStatus_Set));
        for (StopType t : opponentStops) b.addStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Opponents).setStatus(SettingStatus.SettingStatus_Set));
        for (StopType t : myTransient) b.addTransientStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Team).setStatus(SettingStatus.SettingStatus_Set));
        for (StopType t : opponentTransient) b.addTransientStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Opponents).setStatus(SettingStatus.SettingStatus_Set));
        return b.build();
    }

    private static StopType stopOf(PhaseType p) {
        if (p == null) return null;
        switch (p) {
            case UPKEEP: return StopType.StopType_UpkeepStep;
            case DRAW: return StopType.StopType_DrawStep;
            case MAIN1: return StopType.StopType_PrecombatMainPhase;
            case COMBAT_BEGIN: return StopType.StopType_BeginCombatStep;
            case COMBAT_DECLARE_ATTACKERS: return StopType.StopType_DeclareAttackersStep;
            case COMBAT_DECLARE_BLOCKERS: return StopType.StopType_DeclareBlockersStep;
            case COMBAT_FIRST_STRIKE_DAMAGE: return StopType.StopType_FirstStrikeDamageStep;
            case COMBAT_DAMAGE: return StopType.StopType_CombatDamageStep;
            case COMBAT_END: return StopType.StopType_EndCombatStep;
            case MAIN2: return StopType.StopType_PostcombatMainPhase;
            case END_OF_TURN: return StopType.StopType_EndStep;
            default: return null;
        }
    }

    @Override
    public synchronized String toString() {
        return "autoPass=" + autoPass + " default=" + defaultAutoPass + " stack=" + stackAutoPass + " mana=" + manaSelection + " myStops=" + myStops + " oppStops=" + opponentStops
                + (myTransient.isEmpty() && opponentTransient.isEmpty() ? "" : " transient mine=" + myTransient + " opp=" + opponentTransient);
    }
}

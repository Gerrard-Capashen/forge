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
    private AutoPassOption autoPass = AutoPassOption.AutoPassOption_None;          // this turn's option (None/Clear = unset)
    private AutoPassOption defaultAutoPass = AutoPassOption.AutoPassOption_None;
    private AutoPassOption stackAutoPass = AutoPassOption.AutoPassOption_None;
    private ManaSelectionType manaSelection = ManaSelectionType.ManaSelectionType_Auto;

    public synchronized void merge(SettingsMessage s) {
        if (s.getClearAllStops() == SettingStatus.SettingStatus_Set) {
            myStops.clear();
            opponentStops.clear();
        }
        for (Stop st : s.getStopsList()) {
            Set<StopType> target = st.getAppliesTo() == SettingScope.SettingScope_Opponents ? opponentStops : myStops;
            if (st.getStatus() == SettingStatus.SettingStatus_Set) target.add(st.getStopType());
            else if (st.getStatus() == SettingStatus.SettingStatus_Clear) target.remove(st.getStopType());
        }
        if (s.getAutoPassOption() != AutoPassOption.AutoPassOption_None) autoPass = s.getAutoPassOption();
        if (s.getDefaultAutoPassOption() != AutoPassOption.AutoPassOption_None) defaultAutoPass = s.getDefaultAutoPassOption();
        if (s.getStackAutoPassOption() != AutoPassOption.AutoPassOption_None) stackAutoPass = s.getStackAutoPassOption();
        if (s.getManaSelectionType() != ManaSelectionType.ManaSelectionType_None) manaSelection = s.getManaSelectionType();
    }

    private AutoPassOption turnOption() {
        boolean set = autoPass != AutoPassOption.AutoPassOption_None && autoPass != AutoPassOption.AutoPassOption_Clear;
        return set ? autoPass : defaultAutoPass;
    }

    public synchronized boolean fullControl() { return turnOption() == AutoPassOption.AutoPassOption_FullControl; }

    /** Auto-pass the whole turn (unless the opponent does something, for UnlessOpponentAction). */
    public synchronized boolean autoPassTurn() { return turnOption() == AutoPassOption.AutoPassOption_Turn; }

    public synchronized boolean autoPassUnlessOpponentAction() { return turnOption() == AutoPassOption.AutoPassOption_UnlessOpponentAction; }

    public synchronized boolean manualMana() { return manaSelection == ManaSelectionType.ManaSelectionType_Manual || fullControl(); }

    /** Is there a stop on this phase/step for a turn owned by me (or the opponent)? */
    public synchronized boolean stopAt(PhaseType phase, boolean myTurn) {
        StopType t = stopOf(phase);
        return t != null && (myTurn ? myStops : opponentStops).contains(t);
    }

    public synchronized SettingsMessage toMessage() {
        SettingsMessage.Builder b = SettingsMessage.newBuilder().setAutoPassOption(autoPass).setDefaultAutoPassOption(defaultAutoPass)
                .setStackAutoPassOption(stackAutoPass).setManaSelectionType(manaSelection);
        for (StopType t : myStops) b.addStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Team).setStatus(SettingStatus.SettingStatus_Set));
        for (StopType t : opponentStops) b.addStops(Stop.newBuilder().setStopType(t).setAppliesTo(SettingScope.SettingScope_Opponents).setStatus(SettingStatus.SettingStatus_Set));
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
        return "autoPass=" + autoPass + " default=" + defaultAutoPass + " stack=" + stackAutoPass + " mana=" + manaSelection + " myStops=" + myStops + " oppStops=" + opponentStops;
    }
}

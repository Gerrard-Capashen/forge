package forge.mtga;

import com.wizards.mtga.gre.*;
import forge.gui.GuiBase;
import forge.model.FModel;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.ForgeBackend;
import forge.mtga.forge.HeadlessGui;
import forge.mtga.net.MatchSession;

import java.io.File;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Headless regression: ForgeBackend against an in-process fake client that answers every request (randomly, seeded)
 * and checks each GameStateMessage the way the real client merges it (docs/decompiled GreInterface): diff chaining,
 * zone/object consistency, ObjectIdChanged immediately followed by its ZoneTransfer, phase/turn/tap/life annotations.
 *
 * <p>Args: [forgeGuiDir] [clientDir] [games=3] [seed=1]. Props: -Dmtga.deck=playtest (default) -Dselfplay.ai=bolt|plains|bears
 */
public final class SelfPlay {
    public static void main(String[] args) throws Exception {
        File guiDir = new File(args.length > 0 ? args[0] : ".");
        File clientDir = new File(args.length > 1 ? args[1] : "../../client");
        int games = args.length > 2 ? Integer.parseInt(args[2]) : 3;
        long seed = args.length > 3 ? Long.parseLong(args[3]) : 1;
        if (System.getProperty("mtga.deck") == null) System.setProperty("mtga.deck", "playtest");
        GuiBase.setInterface(new HeadlessGui(guiDir));
        FModel.initialize(null, null);
        CardDb db = new CardDb(CardDb.findDatabase(clientDir));

        Map<String, Integer>[] decks = ForgeMtgaMain.decks(System.getProperty("mtga.deck"), System.getProperty("mtga.rig"));
        String aiKind = System.getProperty("selfplay.ai", "bolt");
        if (!"plains".equals(aiKind)) {
            decks[1] = new LinkedHashMap<>();
            if ("bears".equals(aiKind)) { decks[1].put("Forest", 17); decks[1].put("Grizzly Bears", 23); }
            else { decks[1].put("Mountain", 17); decks[1].put("Lightning Bolt", 8); decks[1].put("Grizzly Bears", 15); }
        }
        int failed = 0;
        Map<String, Integer> totals = new TreeMap<>();
        for (int g = 0; g < games; g++) {
            FakeClient fc = new FakeClient(new Random(seed + g));
            ForgeBackend backend = new ForgeBackend(db, decks[0], decks[1]);
            fc.backend = backend;
            ClientToGREMessage.Builder connect = ClientToGREMessage.newBuilder().setType(ClientMessageType.ClientMessageType_ConnectReq);
            connect.getConnectReqBuilder().getDefaultSettingsBuilder();
            backend.onConnect(fc, connect.build());
            boolean done = fc.done.await(Long.getLong("selfplay.timeout", 300), TimeUnit.SECONDS);
            System.out.println("=== game " + (g + 1) + ": " + (done ? "finished" : "TIMEOUT") + " after " + fc.states + " states, turn " + fc.turnNo
                    + ", requests " + fc.requests + ", result " + fc.result);
            fc.problems.forEach((k, v) -> System.out.println("  PROBLEM x" + v + ": " + k));
            System.out.println("  token deletions: " + fc.tokenDeleted);
            fc.annCounts.forEach((k, v) -> totals.merge(k, v, Integer::sum));
            if (!done || !fc.problems.isEmpty()) failed++;
            backend.onDisconnect(fc);
        }
        System.out.println("annotation totals: " + totals);
        System.out.println(failed == 0 ? "SELFPLAY OK" : "SELFPLAY FAILED games=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    static final class FakeClient extends MatchSession {
        final Random rnd;
        ForgeBackend backend;
        final CountDownLatch done = new CountDownLatch(1);
        final Map<String, Integer> problems = new LinkedHashMap<>();
        final Map<String, Integer> annCounts = new TreeMap<>();
        int states, requests, turnNo, mulligans;
        final Map<String, Integer> tokenDeleted = new TreeMap<>();
        private ArrayDeque<Attacker> attackPlan;
        private final Set<Integer> expectedAttackers = new HashSet<>();
        String result = "?";

        private final Map<Integer, GameObjectInfo> objs = new HashMap<>();
        private final Map<Integer, ZoneInfo> zones = new HashMap<>();
        private final Map<Integer, PlayerInfo> players = new HashMap<>();
        private final Map<Integer, AnnotationInfo> persistent = new HashMap<>();
        private TurnInfo turn;
        private String lastCombatDamageStep;
        private int lastGsId;

        FakeClient(Random rnd) { this.rnd = rnd; }

        private void problem(String p) { problems.merge(p, 1, Integer::sum); }

        @Override
        public synchronized void send(MatchServiceToClientMessage.Builder m, int requestId) {
            if (m.hasGreToClientEvent()) {
                for (GREToClientMessage g : m.getGreToClientEvent().getGreToClientMessagesList()) {
                    try {
                        handle(g);
                    } catch (RuntimeException e) {
                        problem("client exception " + e);
                        e.printStackTrace(System.out);
                    }
                }
            }
            if (m.hasMatchGameRoomStateChangedEvent()
                    && m.getMatchGameRoomStateChangedEvent().getGameRoomInfo().getStateType() == MatchGameRoomStateType.MatchGameRoomStateType_MatchCompleted) {
                done.countDown();
            }
        }

        private void reply(ClientToGREMessage.Builder b) { backend.onClientMessage(this, b.build()); }

        private static ClientToGREMessage.Builder msg(ClientMessageType t) { return ClientToGREMessage.newBuilder().setType(t); }

        private void handle(GREToClientMessage g) {
            switch (g.getType()) {
                case GREMessageType_GameStateMessage -> state(g.getGameStateMessage());
                case GREMessageType_MulliganReq -> {
                    requests++;
                    boolean mull = mulligans < 2 && rnd.nextInt(3) == 0;
                    if (mull) mulligans++;
                    if (zone(32).getObjectInstanceIdsCount() != 7) problem("mulligan offered with hand of " + zone(32).getObjectInstanceIdsCount());
                    if (g.getMulliganReq().getMulliganCount() != mulligans - (mull ? 1 : 0)) problem("MulliganReq.mulliganCount mismatch");
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_MulliganResp);
                    r.getMulliganRespBuilder().setDecision(mull ? MulliganOption.MulliganOption_Mulligan : MulliganOption.MulliganOption_AcceptHand);
                    reply(r);
                }
                case GREMessageType_ActionsAvailableReq -> {
                    requests++;
                    List<Action> acts = new ArrayList<>(g.getActionsAvailableReq().getActionsList());
                    for (Action a : acts) {
                        if (a.getActionType() != ActionType.ActionType_Pass && !objs.containsKey(a.getInstanceId())) problem("action on unknown object " + a.getActionType());
                    }
                    acts.removeIf(a -> a.getActionType() == ActionType.ActionType_Pass);
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_PerformActionResp);
                    if (!acts.isEmpty() && rnd.nextInt(10) < 7) r.getPerformActionRespBuilder().addActions(withChosenColor(acts.get(rnd.nextInt(acts.size()))));
                    else r.getPerformActionRespBuilder().addActions(Action.newBuilder().setActionType(ActionType.ActionType_Pass));
                    reply(r);
                }
                case GREMessageType_SelectTargetsReq -> {
                    requests++;
                    TargetSelection ts = g.getSelectTargetsReq().getTargets(0);
                    if (!g.hasPrompt()) problem("SelectTargetsReq without prompt");
                    for (Target t : ts.getTargetsList()) {
                        int id = t.getTargetInstanceId();
                        if (id > 2 && !objs.containsKey(id)) problem("target id not in state");
                    }
                    // like the client: click a target, wait for the echo (picked = Unselect, selectedTargets), then submit
                    int picked = 0;
                    for (Target t : ts.getTargetsList()) if (t.getLegalAction() == SelectAction.SelectAction_Unselect) picked++;
                    if (picked != ts.getSelectedTargets()) problem("SelectTargetsReq selectedTargets != Unselect targets");
                    List<Target> open = new ArrayList<>();
                    for (Target t : ts.getTargetsList()) if (t.getLegalAction() == SelectAction.SelectAction_Select) open.add(t);
                    if (picked >= Math.max(1, ts.getMinTargets()) || open.isEmpty()) {
                        reply(msg(ClientMessageType.ClientMessageType_SubmitTargetsReq));
                        break;
                    }
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_SelectTargetsResp);
                    Target pick = open.get(rnd.nextInt(open.size()));
                    r.getSelectTargetsRespBuilder().getTargetBuilder().setTargetIdx(ts.getTargetIdx()).addTargets(pick);
                    reply(r);
                }
                case GREMessageType_CastingTimeOptionsReq -> {
                    requests++;
                    List<CastingTimeOptionReq> opts = g.getCastingTimeOptionsReq().getCastingTimeOptionReqList();
                    for (CastingTimeOptionReq o : opts) {
                        // CastingTimeOptionRequest throws NotImplementedException for these (the duel crashes)
                        switch (o.getCastingTimeOptionType()) {
                            case CastingTimeOptionType_OptionalCost, CastingTimeOptionType_Multikicker, CastingTimeOptionType_CastThroughAbility,
                                 CastingTimeOptionType_OptionalCostPaid, CastingTimeOptionType_None -> problem("CastingTimeOption type the client cannot show: " + o.getCastingTimeOptionType());
                            default -> { }
                        }
                    }
                    CastingTimeOptionReq pick = opts.get(rnd.nextInt(opts.size()));
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_CastingTimeOptionsResp);
                    r.getCastingTimeOptionsRespBuilder().getCastingTimeOptionRespBuilder().setCtoId(pick.getCtoId())
                            .setCastingTimeOptionType(pick.getCastingTimeOptionType());
                    reply(r);
                }
                case GREMessageType_OptionalActionMessage -> {
                    requests++;
                    OptionalActionMessage oam = g.getOptionalActionMessage();
                    if (oam.getOptionalActionTypesList().contains(CardMechanicType.CardMechanicType_Explore)) {
                        if (oam.getRecipientIdsCount() == 0) problem("Explore prompt without the revealed card");
                        for (int id : oam.getRecipientIdsList()) {
                            GameObjectInfo o = objs.get(id);
                            if (o == null || o.getVisibility() == Visibility.Visibility_Hidden) problem("Explore card not visible to the client");
                        }
                    }
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_OptionalActionResp);
                    r.getOptionalRespBuilder().setResponse(rnd.nextBoolean() ? OptionResponse.OptionResponse_Allow_Yes : OptionResponse.OptionResponse_Cancel_No);
                    reply(r);
                }
                case GREMessageType_SelectNReq -> {
                    requests++;
                    SelectNReq sn = g.getSelectNReq();
                    if (!g.hasPrompt()) problem("SelectNReq without prompt (the client NREs in SelectNWorkflowTranslation)");
                    List<Integer> ids = new ArrayList<>(sn.getIdsList());
                    if (sn.getIdType() == IdType.IdType_InstanceId) {
                        for (int id : ids) {
                            GameObjectInfo o = objs.get(id);
                            if (o == null) problem("SelectN id not in state (" + sn.getContext() + ")");
                            else if (o.getVisibility() == Visibility.Visibility_Hidden) problem("SelectN offers a hidden card (" + sn.getContext() + ")");
                        }
                    }
                    Collections.shuffle(ids, rnd);
                    int n = Math.max(sn.getMinSel(), Math.min((int) sn.getMaxSel(), sn.getMinSel() + rnd.nextInt(2)));
                    if (sn.getIdType() == IdType.IdType_AbilityGrpId) n = Math.max(1, n);
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_SelectNResp);
                    r.getSelectNRespBuilder().addAllIds(ids.subList(0, Math.min(n, ids.size())));
                    reply(r);
                }
                case GREMessageType_GroupReq -> {
                    requests++;
                    GroupReq gr = g.getGroupReq();
                    List<Integer> ids = new ArrayList<>(gr.getInstanceIdsList());
                    for (int id : ids) {
                        GameObjectInfo o = objs.get(id);
                        if (o == null || o.getVisibility() == Visibility.Visibility_Hidden) problem("GroupReq card not visible (" + gr.getContext() + ")");
                    }
                    Collections.shuffle(ids, rnd);
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_GroupResp);
                    int idx = 0;
                    for (int i = 0; i < gr.getGroupSpecsCount(); i++) {
                        GroupSpecification spec = gr.getGroupSpecs(i);
                        boolean last = i == gr.getGroupSpecsCount() - 1;
                        int take = last ? ids.size() - idx : Math.min(ids.size() - idx, (int) spec.getLowerBound() + (spec.getUpperBound() > spec.getLowerBound() ? rnd.nextInt((int) (spec.getUpperBound() - spec.getLowerBound()) + 1) : 0));
                        r.getGroupRespBuilder().addGroups(Group.newBuilder().addAllIds(ids.subList(idx, idx + take))
                                .setZoneType(spec.getZoneType()).setSubZoneType(spec.getSubZoneType()));
                        idx += take;
                    }
                    reply(r);
                }
                case GREMessageType_NumericInputReq -> {
                    requests++;
                    NumericInputReq ni = g.getNumericInputReq();
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_NumericInputResp);
                    r.getNumericInputRespBuilder().setNumericInputValue(ni.getMinValue() + rnd.nextInt((int) (ni.getMaxValue() - ni.getMinValue()) + 1));
                    reply(r);
                }
                case GREMessageType_OrderReq -> {
                    requests++;
                    List<Integer> ids = new ArrayList<>(g.getOrderReq().getIdsList());
                    Collections.shuffle(ids, rnd);
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_OrderResp);
                    r.getOrderRespBuilder().addAllIds(ids);
                    reply(r);
                }
                case GREMessageType_PayCostsReq -> {
                    requests++;
                    if (g.getPayCostsReq().hasEffectCostReq()) {      // non-mana cost choice (ninjutsu return, sacrifice...)
                        SelectNReq sel = g.getPayCostsReq().getEffectCostReq().getCostSelection();
                        if (sel.getIdsCount() < sel.getMinSel()) problem("cost selection with fewer options than required");
                        for (int id : sel.getIdsList()) {
                            GameObjectInfo o = objs.get(id);
                            if (o == null || o.getVisibility() == Visibility.Visibility_Hidden) problem("cost selection option not visible");
                        }
                        List<Integer> ids = new ArrayList<>(sel.getIdsList());
                        java.util.Collections.shuffle(ids, rnd);
                        ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_EffectCostResp);
                        r.getEffectCostRespBuilder().setEffectCostType(EffectCostType.EffectCostType_Select)
                                .getCostSelectionBuilder().addAllIds(ids.subList(0, Math.min(ids.size(), sel.getMinSel())));
                        reply(r);
                        break;
                    }
                    List<Action> pay = g.getPayCostsReq().getPaymentActions().getActionsList();
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_PerformActionResp);
                    r.getPerformActionRespBuilder().addActions(withChosenColor(pay.get(rnd.nextInt(pay.size()))));
                    reply(r);
                }
                case GREMessageType_DeclareAttackersReq -> {
                    requests++;
                    DeclareAttackersReq dr = g.getDeclareAttackersReq();
                    if (attackPlan == null) {      // new declaration: all-attack, none, or click attackers one by one (deltas)
                        attackPlan = new ArrayDeque<>();
                        expectedAttackers.clear();
                        int mode = rnd.nextInt(3);
                        if (mode == 0) {      // "All Attack" only selects; the echo must show everyone, then we submit
                            for (Attacker a : dr.getAttackersList()) expectedAttackers.add(a.getAttackerInstanceId());
                            ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_DeclareAttackersResp);
                            r.getDeclareAttackersRespBuilder().setAutoDeclare(true);
                            reply(r);
                            break;
                        }
                        if (mode == 2) {
                            for (Attacker a : dr.getAttackersList()) attackPlan.add(a.toBuilder().setSelectedDamageRecipient(a.getLegalDamageRecipients(0)).build());
                            if (!attackPlan.isEmpty() && rnd.nextBoolean()) attackPlan.add(attackPlan.peekFirst().toBuilder().clearSelectedDamageRecipient().build());
                        }
                    } else {
                        Set<Integer> echoed = new HashSet<>();
                        for (Attacker a : dr.getAttackersList()) if (a.hasSelectedDamageRecipient()) echoed.add(a.getAttackerInstanceId());
                        if (!echoed.equals(expectedAttackers)) problem("DeclareAttackersReq echo " + echoed + " != toggled selection " + expectedAttackers);
                    }
                    Attacker next = attackPlan.poll();
                    if (next == null) {
                        attackPlan = null;
                        reply(msg(ClientMessageType.ClientMessageType_SubmitAttackersReq));
                        break;
                    }
                    if (next.hasSelectedDamageRecipient()) expectedAttackers.add(next.getAttackerInstanceId());
                    else expectedAttackers.remove(next.getAttackerInstanceId());
                    ClientToGREMessage.Builder r = msg(ClientMessageType.ClientMessageType_DeclareAttackersResp);
                    r.getDeclareAttackersRespBuilder().addSelectedAttackers(next);
                    reply(r);
                }
                case GREMessageType_DeclareBlockersReq -> {
                    requests++;
                    reply(msg(ClientMessageType.ClientMessageType_SubmitBlockersReq));
                }
                case GREMessageType_IntermissionReq -> result = g.getIntermissionReq().getResult().getResult() + " team " + g.getIntermissionReq().getResult().getWinningTeamId();
                case GREMessageType_ConnectResp, GREMessageType_SubmitTargetsResp, GREMessageType_SubmitAttackersResp,
                     GREMessageType_SubmitBlockersResp, GREMessageType_SetSettingsResp -> { }
                default -> problem("fake client does not handle " + g.getType());
            }
            if (g.getType() != GREMessageType.GREMessageType_GameStateMessage && g.getGameStateId() != lastGsId && g.getGameStateId() != 0) {
                problem(g.getType() + " gameStateId " + g.getGameStateId() + " != last state " + lastGsId);
            }
        }

        private ZoneInfo zone(int id) { return zones.getOrDefault(id, ZoneInfo.getDefaultInstance()); }

        private void state(GameStateMessage gs) {
            states++;
            Map<Integer, GameObjectInfo> oldObjs = new HashMap<>(objs);
            Map<Integer, ZoneInfo> oldZones = new HashMap<>(zones);
            Map<Integer, PlayerInfo> oldPlayers = new HashMap<>(players);
            TurnInfo oldTurn = turn;
            if (gs.getType() == GameStateType.GameStateType_Full) {
                objs.clear(); zones.clear(); players.clear(); persistent.clear();
            } else if (gs.getPrevGameStateId() != lastGsId) {
                problem("diff prevGameStateId " + gs.getPrevGameStateId() + " != " + lastGsId);
            }
            lastGsId = gs.getGameStateId();
            for (GameObjectInfo o : gs.getGameObjectsList()) objs.put(o.getInstanceId(), o);
            for (ZoneInfo z : gs.getZonesList()) zones.put(z.getZoneId(), z);
            for (PlayerInfo p : gs.getPlayersList()) players.put(p.getSystemSeatNumber(), p);
            for (int id : gs.getDiffDeletedInstanceIdsList()) objs.remove(id);
            for (AnnotationInfo a : gs.getPersistentAnnotationsList()) persistent.put(a.getId(), a);
            for (int id : gs.getDiffDeletedPersistentAnnotationIdsList()) {
                if (persistent.remove(id) == null) problem("deleted unknown persistent annotation");
            }
            if (gs.hasTurnInfo()) turn = gs.getTurnInfo();
            if (turn != null) turnNo = turn.getTurnNumber();

            // structural consistency
            Map<Integer, Integer> seen = new HashMap<>();
            for (ZoneInfo z : zones.values()) {
                for (int id : z.getObjectInstanceIdsList()) {
                    if (seen.put(id, z.getZoneId()) != null) problem("object listed in two zones");
                    GameObjectInfo o = objs.get(id);
                    boolean hiddenZone = z.getType() == ZoneType.ZoneType_Library || (z.getType() == ZoneType.ZoneType_Hand && z.getOwnerSeatId() != 1);
                    if (o == null && !hiddenZone) problem("public zone " + z.getType() + " lists id without object");
                    if (o != null && o.getZoneId() != z.getZoneId()) problem("object zoneId differs from listing zone");
                }
            }
            for (GameObjectInfo o : objs.values()) {
                if (!seen.containsKey(o.getInstanceId())) problem("object not listed by any zone (" + o.getType() + ")");
                if (o.getVisibility() != Visibility.Visibility_Hidden && o.getGrpId() == 0) problem("visible object without grpId: " + backend.debugName(o.getInstanceId()));
            }
            for (AnnotationInfo p : persistent.values()) {
                for (int id : p.getAffectedIdsList()) if (!objs.containsKey(id)) problem("persistent annotation on missing object " + p.getType(0));
            }
            // the client replaces its GameInfo with any it receives: MatchState None hides Concede, a missing format resets it
            if (gs.hasGameInfo() && gs.getGameInfo().getMatchState() == com.wizards.mtga.gre.MatchState.MatchState_None) {
                problem("GameInfo without matchState (client would lose MatchState/Concede)");
            }
            if (gs.hasGameInfo() && gs.getGameInfo().getSuperFormat() == com.wizards.mtga.gre.SuperFormat.SuperFormat_None) {
                problem("GameInfo without superFormat");
            }
            if (gs.getType() == GameStateType.GameStateType_Full) {
                // zones the client dereferences without null checks (MtgGameState.LocalSideboard etc.)
                for (ZoneType t : List.of(ZoneType.ZoneType_Sideboard, ZoneType.ZoneType_Revealed, ZoneType.ZoneType_PhasedOut, ZoneType.ZoneType_Limbo)) {
                    boolean found = false;
                    for (ZoneInfo z : zones.values()) found |= z.getType() == t;
                    if (!found) problem("Full state lacks a " + t + " zone");
                }
                return;
            }

            // the final (game over) diff must not take the board away: the client keeps showing it behind the result
            if (gs.hasGameInfo() && gs.getGameInfo().getMatchState() == com.wizards.mtga.gre.MatchState.MatchState_MatchComplete
                    && (gs.getDiffDeletedInstanceIdsCount() > 0 || gs.getZonesCount() > 0)) {
                problem("game-over diff changes zones/deletes objects (board disappears behind VICTORY)");
            }
            // all combat damage of one step in ONE diff (the client makes one combat frame per diff: two diffs = two hits)
            boolean combatDamage = false;
            for (AnnotationInfo a : gs.getAnnotationsList()) {
                if (a.getType(0) == AnnotationType.AnnotationType_DamageDealt && detail(a, "type") == 1) combatDamage = true;
            }
            if (combatDamage && turn != null) {
                String key = turn.getTurnNumber() + "/" + turn.getStep();
                if (key.equals(lastCombatDamageStep)) problem("combat damage of one step split over several diffs");
                lastCombatDamageStep = key;
            }

            // annotations
            List<AnnotationInfo> anns = gs.getAnnotationsList();
            Set<Integer> transferred = new HashSet<>(), created = new HashSet<>(), tappedAnn = new HashSet<>();
            Map<Integer, Integer> lifeAnn = new HashMap<>();
            boolean newTurn = false, phaseAnn = false;
            for (int i = 0; i < anns.size(); i++) {
                AnnotationInfo a = anns.get(i);
                AnnotationType t = a.getType(0);
                annCounts.merge(t.name().replace("AnnotationType_", ""), 1, Integer::sum);
                switch (t) {
                    case AnnotationType_ObjectIdChanged -> {
                        int orig = detail(a, "orig_id"), nid = detail(a, "new_id");
                        if (!oldObjs.containsKey(orig) && !inAnyZone(oldZones, orig)) {
                            AnnotationInfo zt = i + 1 < anns.size() ? anns.get(i + 1) : a;
                            problem("ObjectIdChanged orig_id unknown to the client (" + detail(zt, "zone_src") + "->" + detail(zt, "zone_dest") + " " + category(zt) + ")");
                        }
                        AnnotationInfo next = i + 1 < anns.size() ? anns.get(i + 1) : null;
                        if (next == null || next.getType(0) != AnnotationType.AnnotationType_ZoneTransfer || next.getAffectedIds(0) != nid) {
                            problem("ObjectIdChanged not immediately followed by ZoneTransfer of new id");
                        }
                    }
                    case AnnotationType_ZoneTransfer -> {
                        int id = a.getAffectedIds(0), src = detail(a, "zone_src"), dst = detail(a, "zone_dest");
                        transferred.add(id);
                        if (!zones.containsKey(src) || !zones.containsKey(dst)) problem("ZoneTransfer zone unknown");
                        if (!zone(dst).getObjectInstanceIdsList().contains(id)) problem("ZoneTransfer dest zone does not list object");
                        if (category(a) == null) problem("ZoneTransfer without category");
                    }
                    case AnnotationType_TappedUntappedPermanent -> {
                        int id = a.getAffectedIds(0);
                        tappedAnn.add(id);
                        if (!oldObjs.containsKey(id)) problem("TappedUntapped on object not in old state");
                    }
                    case AnnotationType_ModifiedLife -> lifeAnn.merge(a.getAffectedIds(0), detail(a, "life"), Integer::sum);
                    case AnnotationType_NewTurnStarted -> {
                        newTurn = true;
                        if (a.getAffectorId() != turn.getActivePlayer()) problem("NewTurnStarted affector is not the active player");
                    }
                    case AnnotationType_PhaseOrStepModified -> {
                        phaseAnn = true;
                        if (detail(a, "phase") != turn.getPhase().getNumber() || detail(a, "step") != turn.getStep().getNumber()) problem("PhaseOrStepModified details differ from TurnInfo");
                    }
                    case AnnotationType_TokenCreated, AnnotationType_AbilityInstanceCreated -> {
                        created.addAll(a.getAffectedIdsList());
                        for (int id : a.getAffectedIdsList()) if (!objs.containsKey(id)) problem(t + " for object not in state");
                    }
                    case AnnotationType_AbilityInstanceDeleted, AnnotationType_TokenDeleted -> {
                        for (int id : a.getAffectedIdsList()) if (!oldObjs.containsKey(id)) problem(t + " for object the client never had");
                        if (t == AnnotationType.AnnotationType_TokenDeleted) for (int id : a.getAffectedIdsList()) tokenDeleted.merge(backend.debugName(id), 1, Integer::sum);
                    }
                    case AnnotationType_CounterAdded, AnnotationType_CounterRemoved -> {
                        if (!oldObjs.containsKey(a.getAffectedIds(0))) problem(t + " on object not in old state");
                    }
                    case AnnotationType_ResolutionStart, AnnotationType_ResolutionComplete -> {
                        int id = a.getAffectorId();
                        if (!oldObjs.containsKey(id) && !objs.containsKey(id)) problem(t + " affector unknown");
                    }
                    default -> { }
                }
            }
            if (oldTurn != null && turn != null) {
                if (turn.getTurnNumber() != oldTurn.getTurnNumber() && !newTurn) problem("turn changed without NewTurnStarted");
                if ((turn.getPhase() != oldTurn.getPhase() || turn.getStep() != oldTurn.getStep()) && !phaseAnn && turn.getPhase() != Phase.Phase_None) problem("phase/step changed without PhaseOrStepModified");
            }
            for (PlayerInfo p : players.values()) {
                PlayerInfo o = oldPlayers.get(p.getSystemSeatNumber());
                if (o != null && o.getLifeTotal() != p.getLifeTotal()
                        && lifeAnn.getOrDefault(p.getSystemSeatNumber(), 0) != p.getLifeTotal() - o.getLifeTotal()) problem("life changed without matching ModifiedLife");
            }
            for (GameObjectInfo o : objs.values()) {
                GameObjectInfo old = oldObjs.get(o.getInstanceId());
                if (old != null && old.getZoneId() == 28 && o.getZoneId() == 28 && old.getIsTapped() != o.getIsTapped() && !tappedAnn.contains(o.getInstanceId())) {
                    problem("tap state changed without TappedUntappedPermanent");
                }
                if (old == null && !transferred.contains(o.getInstanceId()) && !created.contains(o.getInstanceId())
                        && !inAnyZone(oldZones, o.getInstanceId()) && o.getZoneId() != 25) {
                    problem("new object without ZoneTransfer/TokenCreated/AbilityInstanceCreated (zone " + o.getZoneId() + " " + backend.debugName(o.getInstanceId()) + ")");
                }
            }
        }

        private static boolean inAnyZone(Map<Integer, ZoneInfo> zs, int id) {
            for (ZoneInfo z : zs.values()) if (z.getObjectInstanceIdsList().contains(id)) return true;
            return false;
        }

        /** Like the client's color wheel: a mana action with several ManaPaymentOptions comes back with the chosen one only. */
        private Action withChosenColor(Action a) {
            if (a.getManaPaymentOptionsCount() < 2) return a;
            for (ManaPaymentOption o : a.getManaPaymentOptionsList()) {
                for (ManaInfo m : o.getManaList()) {
                    boolean predictive = false;
                    for (ManaInfo.Spec sp : m.getSpecsList()) predictive |= sp.getType() == ManaSpecType.ManaSpecType_Predictive;
                    if (!predictive) problem("mana option without Predictive spec (client shows no color wheel)");
                }
            }
            ManaPaymentOption pick = a.getManaPaymentOptions(rnd.nextInt(a.getManaPaymentOptionsCount()));
            return a.toBuilder().clearManaPaymentOptions().addManaPaymentOptions(pick).build();
        }

        private static int detail(AnnotationInfo a, String key) {
            for (KeyValuePairInfo d : a.getDetailsList()) if (d.getKey().equals(key) && d.getValueInt32Count() > 0) return d.getValueInt32(0);
            return Integer.MIN_VALUE;
        }

        private static String category(AnnotationInfo a) {
            for (KeyValuePairInfo d : a.getDetailsList()) if (d.getKey().equals("category") && d.getValueStringCount() > 0) return d.getValueString(0);
            return null;
        }
    }
}

package forge.mtga.forge;

import com.google.gson.JsonObject;
import forge.card.ColorSet;
import forge.game.card.CardView;
import forge.game.event.GameEvent;
import forge.game.event.GameEventAttackersDeclared;
import forge.game.event.GameEventCardChangeZone;
import forge.game.event.GameEventLandPlayed;
import forge.game.event.GameEventPlayerDamaged;
import forge.game.event.GameEventSpellAbilityCast;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

import java.util.Map;
import java.util.TreeMap;

/**
 * Gameplay counters of one seat for the daily quests (coordinator/src/quests.ts), fed by the
 * Forge game's events: creatures attacked with, spells cast (per colour, instants/sorceries, creatures, artifacts/enchantments), lands
 * played, damage dealt to opponents, opponent creatures that died. The names are the quest metrics the coordinator knows.
 */
public final class QuestMetrics {
    private final Map<String, Integer> counts = new TreeMap<>();
    private PlayerView me;

    /** Finished matches' counters of this server's player, by match id, until the Front Door reports them (bot matches, the host's own seat, a guest's frame). */
    private static final Map<String, JsonObject> BY_MATCH = new java.util.concurrent.ConcurrentHashMap<>();

    public static void publish(String matchId, JsonObject metrics) { if (matchId != null && metrics != null) BY_MATCH.put(matchId, metrics); }

    /** This server's player's counters of a finished match (removed), null when none arrived. */
    public static JsonObject take(String matchId) { return matchId == null ? null : BY_MATCH.remove(matchId); }

    void bind(PlayerView player) { me = player; }

    private void add(String metric, int n) { if (n > 0) counts.merge(metric, n, Integer::sum); }

    synchronized void on(GameEvent ev) {
        if (me == null) return;
        if (ev instanceof GameEventAttackersDeclared a) {
            if (same(a.player(), me)) add("CreaturesAttacked", a.attackersMap().size());
        } else if (ev instanceof GameEventLandPlayed l) {
            if (same(l.player(), me)) add("LandsPlayed", 1);
        } else if (ev instanceof GameEventSpellAbilityCast c) {
            if (c.sa() == null || !c.sa().isSpell() || c.si() == null || !same(c.si().getActivatingPlayer(), me)) return;
            CardView card = c.sa().getHostCard();
            if (card == null || card.getCurrentState() == null) return;
            add("SpellsCast", 1);
            ColorSet colors = card.getCurrentState().getColors();
            if (colors.hasWhite()) add("WhiteSpellsCast", 1);
            if (colors.hasBlue()) add("BlueSpellsCast", 1);
            if (colors.hasBlack()) add("BlackSpellsCast", 1);
            if (colors.hasRed()) add("RedSpellsCast", 1);
            if (colors.hasGreen()) add("GreenSpellsCast", 1);
            var type = card.getCurrentState().getType();
            if (type.isInstant() || type.isSorcery()) add("InstantsAndSorceriesCast", 1);
            if (type.isCreature()) add("CreaturesCast", 1);
            if (type.isArtifact() || type.isEnchantment()) add("ArtifactsAndEnchantmentsCast", 1);
        } else if (ev instanceof GameEventPlayerDamaged d) {
            if (d.target() != null && !same(d.target(), me) && d.source() != null && same(d.source().getController(), me)) add("DamageDealt", d.amount());
        } else if (ev instanceof GameEventCardChangeZone z) {
            if (z.card() == null || z.from() == null || z.to() == null) return;
            if (z.from().zoneType() == ZoneType.Battlefield && z.to().zoneType() == ZoneType.Graveyard
                    && z.card().getCurrentState() != null && z.card().getCurrentState().getType().isCreature()
                    && z.card().getController() != null && !same(z.card().getController(), me)) add("OpponentCreaturesDied", 1);
        }
    }

    private static boolean same(PlayerView a, PlayerView b) { return a != null && b != null && a.getId() == b.getId(); }

    public synchronized JsonObject toJson() {
        JsonObject o = new JsonObject();
        counts.forEach(o::addProperty);
        return o;
    }
}

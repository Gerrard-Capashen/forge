package forge.mtga.forge;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Maps a Forge SpellAbility to the client's ability id (Abilities.Id in the card DB): same category (activated /
 * triggered / spell / alternative cost), same loyalty cost for planeswalkers, then best text overlap.
 */
final class AbilityMap {
    private final CardDb db;

    AbilityMap(CardDb db) { this.db = db; }

    /** Client ability id for sa on a card with the given grpId; 0 if nothing plausible. */
    int abilityId(SpellAbility sa, int cardGrp) {
        if (sa == null) return 0;
        // an ability granted by another card (Black Mage's Rod: "equipped creature has 'Whenever you cast...'" on its Hero
        // token; Tabernacle of Pendrell Vale's upkeep trigger on every creature) is printed on the granting card, not on its holder
        Card host = sa.getHostCard(), from = sa.getOriginalHost();
        if (sa.isTrigger() && sa.getTrigger() != null && sa.getTrigger().getOriginalHost() != null) from = sa.getTrigger().getOriginalHost();
        if (from != null && from != host && !from.getName().equals(host.getName())) {
            int id = match(sa, db.grpId(from.getName()), true);
            if (id != 0) return id;
        }
        int granted = grantedLoyaltyAbility(sa);
        if (granted != 0) return granted;
        int id = match(sa, cardGrp, false);
        // a mana ability granted by a land type (Yavimaya, Cradle of Growth: every land is a Forest) is not one of the card's own
        if (id != 0 && sa.isManaAbility() && !db.abilities(cardGrp).isEmpty() && !ownManaText(id, cardGrp)) {
            int basic = basicManaAbility(sa);
            if (basic != 0) return basic;
        }
        return id;
    }

    /** A loyalty ability another card grants to planeswalkers (Ichormoon Gauntlet): its own DB ability, found by loyalty cost and text; 0 if sa is the card's own. */
    int grantedLoyaltyAbility(SpellAbility sa) {
        if (sa == null || !sa.isPwAbility()) return 0;
        Card host = sa.getHostCard(), from = sa.getOriginalHost();
        if (host == null || from == null || from == host || from.getName().equals(host.getName())) return 0;
        String desc = describe(sa);
        String cost = loyaltyPrefix(desc);
        if (cost == null) return 0;
        String text = desc.replaceFirst("^\\s*\\[?[+−-]?(\\d+|X)\\]?\\s*:\\s*", "");
        return db.loyaltyAbilityByText(cost, words(text, host));
    }

    private boolean ownManaText(int id, int cardGrp) {
        for (CardDb.Ability a : db.abilities(cardGrp)) {
            if (a.id() == id) return a.text() != null && a.text().toLowerCase().contains("add ");
        }
        return false;
    }

    private int match(SpellAbility sa, int cardGrp, boolean strict) {
        if (sa == null || cardGrp == 0) return 0;
        List<CardDb.Ability> abs = db.abilities(cardGrp);
        if (abs.isEmpty()) return sa.isManaAbility() ? basicManaAbility(sa) : 0;
        String desc = describe(sa);
        String loyalty = loyaltyPrefix(desc);
        Set<String> words = words(desc, sa.getHostCard());
        if (strict) {
            // the granting card prints it as a static ("All creatures have '...'", category 3), so any category counts:
            // only a close text match is trusted
            int found = 0;
            double top = 0.3;
            for (CardDb.Ability a : abs) {
                double s = overlap(words, words(a.text(), null));
                if (s > top) { top = s; found = a.id(); }
            }
            return found;
        }
        // the sacrifice trigger of an evoked creature is the keyword's own, shown on Arena as the "Evoke {cost}" ability (not as the enters trigger)
        if (sa.isTrigger() && desc != null && desc.toLowerCase().contains("evoke") && desc.toLowerCase().contains("sacrifice")) {
            for (CardDb.Ability a : abs) if (a.text() != null && a.text().startsWith("Evoke")) return a.id();
        }
        int want = sa.isTrigger() ? 2 : sa.isSpell() ? 4 : 1;
        int best = 0;
        double bestScore = -1;
        for (CardDb.Ability a : abs) {
            double score = 0;
            if (a.category() == want) score += 2;
            else if (want == 4 && a.category() == 8) score += sa.getAlternativeCost() != null ? 3 : 0;
            else if (want == 1 && a.category() != 1) continue;
            else if (want == 2 && a.category() != 2) continue;
            if (loyalty != null && a.loyaltyCost() != null && loyalty.equals(a.loyaltyCost().replace("−", "-"))) score += 5;
            score += overlap(words, words(a.text(), sa.getHostCard()));
            if (score > bestScore) { bestScore = score; best = a.id(); }
        }
        if (want == 2) {
            // a trigger set up by a non-triggered ability (Devourer of Destiny's upkeep look, created by its opening-hand
            // reveal, category 9) showed the card's unrelated "when you cast" trigger: a much closer text wins
            double triggerOverlap = best == 0 ? 0 : bestScore - 2;
            for (CardDb.Ability a : abs) {
                if (a.category() == 2 || a.category() == 1) continue;
                double s = overlap(words, words(a.text(), sa.getHostCard()));
                if (s >= 0.4 && s > triggerOverlap + 0.25) { triggerOverlap = s; best = a.id(); }
            }
        }
        if (best == 0) {      // e.g. a delayed trigger set up by an activated ability: show the closest printed ability
            for (CardDb.Ability a : abs) {
                double score = overlap(words, words(a.text(), sa.getHostCard()));
                if (score > bestScore) { bestScore = score; best = a.id(); }
            }
        }
        return best;
    }

    /** The card's printed ability whose text best matches (used for replacement effects such as shocklands); 0 if weak. */
    int abilityByText(int cardGrp, String text, Card host) {
        Set<String> words = words(text, host);
        int best = 0;
        double bestScore = 0.3;
        for (CardDb.Ability a : db.abilities(cardGrp)) {
            double s = overlap(words, words(a.text(), host));
            if (s > bestScore) { bestScore = s; best = a.id(); }
        }
        return best;
    }

    /** Basic land types' intrinsic "{T}: Add X" abilities (ids 1001-1005; the card DB lists none for basics). */
    static List<Integer> basicLandAbilities(Card c) {
        List<Integer> out = new java.util.ArrayList<>();
        String[] types = {"Plains", "Island", "Swamp", "Mountain", "Forest"};
        for (int i = 0; i < types.length; i++) if (c.getType().hasSubtype(types[i])) out.add(1001 + i);
        return out;
    }

    private static int basicManaAbility(SpellAbility sa) {
        String produced = sa.getManaPart() == null ? "" : sa.getManaPart().getOrigProduced();
        String[] codes = {"W", "U", "B", "R", "G"};
        for (int i = 0; i < codes.length; i++) if (codes[i].equals(produced)) return 1001 + i;
        return 0;
    }

    private static String describe(SpellAbility sa) {
        try {
            if (sa.isTrigger() && sa.getTrigger() != null) return sa.getTrigger().toString();
        } catch (RuntimeException ignored) { }
        String d = sa.getDescription();
        return d == null || d.isBlank() ? sa.toString() : d;
    }

    private static String loyaltyPrefix(String desc) {
        if (desc == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*\\[?([+−-]?\\d+|[+−-]X|0)\\]?\\s*:").matcher(desc);
        return m.find() ? m.group(1).replace("−", "-") : null;
    }

    private static Set<String> words(String text, Card host) {
        if (text == null) return Set.of();
        String t = text.toLowerCase().replace("cardname", " ").replaceAll("\\{o([a-z0-9]+)\\}", "{$1}");
        if (host != null) t = t.replace(host.getName().toLowerCase(), " ");
        Set<String> out = new HashSet<>(Arrays.asList(t.split("[^a-z0-9{}+/-]+")));
        out.remove("");
        out.removeAll(Set.of("the", "a", "an", "of", "to", "your", "you", "it", "that", "and", "this"));
        return out;
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int common = 0;
        for (String w : a) if (b.contains(w)) common++;
        return (double) common / (a.size() + b.size() - common);
    }
}

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
        if (sa == null || cardGrp == 0) return 0;
        List<CardDb.Ability> abs = db.abilities(cardGrp);
        if (abs.isEmpty()) return sa.isManaAbility() ? basicManaAbility(sa) : 0;
        String desc = describe(sa);
        String loyalty = loyaltyPrefix(desc);
        Set<String> words = words(desc, sa.getHostCard());
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
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^\\s*([+−-]?\\d+|[+−-]X|0)\\s*:").matcher(desc);
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

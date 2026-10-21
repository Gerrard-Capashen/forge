package forge.mtga;

import forge.deck.CardArchetypeLDAGenerator;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.DeckgenUtil;
import forge.deck.io.Archetype;
import forge.deck.io.DeckSerializer;
import forge.game.GameFormat;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Decks for the Forge AI in Front Door matches. {@code -Dmtga.aideck=} a .dck path, a precon name from
 * res/quest/precons ("MTGA Chaos and Mayhem"), or "random" (default: a random "MTGA ..." precon, whose cards all
 * exist in the client).
 */
public final class AiDecks {
    private static final Random RND = new Random();

    private AiDecks() { }

    public record Choice(String name, Map<String, Integer> cards) { }

    public static Choice pick(String spec) {
        File precons = new File(ForgeConstants.QUEST_PRECON_DIR);
        File f = null;
        if (spec != null && !spec.isBlank() && !spec.equalsIgnoreCase("random")) {
            f = new File(spec);
            if (!f.isFile()) f = new File(precons, spec.endsWith(".dck") ? spec : spec + ".dck");
            if (!f.isFile()) {
                System.out.println("[mtga] AI deck '" + spec + "' not found; using a random MTGA precon");
                f = null;
            }
        }
        if (f == null) {
            File[] all = precons.listFiles((d, n) -> n.startsWith("MTGA ") && n.endsWith(".dck"));
            if (all == null || all.length == 0) all = precons.listFiles((d, n) -> n.endsWith(".dck"));
            if (all == null || all.length == 0) throw new IllegalStateException("no precons in " + precons);
            Arrays.sort(all);
            f = all[RND.nextInt(all.length)];
        }
        Deck d = DeckSerializer.fromFile(f);
        Map<String, Integer> cards = mainCards(d);
        String name = d.getName() == null ? f.getName().replace(".dck", "") : d.getName();
        return new Choice(name.replaceFirst("^MTGA ", ""), cards);
    }

    /**
     * A deck for the AI in a Forge format, made only of cards the client can show: Forge's archetype generator
     * (res/deckgendecks) first, keeping the try with the fewest cards the client lacks and replacing those by basic
     * lands; a random color deck from the format's cards on the client if the format has no archetypes.
     */
    public static Choice forFormat(GameFormat format, Predicate<String> onClient) {
        String key = format.getName();
        try {
            if (!CardArchetypeLDAGenerator.ldaArchetypes.containsKey(key)) CardArchetypeLDAGenerator.initializeFormat(key);
            List<Archetype> archetypes = CardArchetypeLDAGenerator.ldaArchetypes.get(key);
            Choice best = null;
            int bestMissing = Integer.MAX_VALUE;
            for (int i = 0; archetypes != null && !archetypes.isEmpty() && i < 20 && bestMissing > 0; i++) {
                Archetype a = archetypes.get(RND.nextInt(archetypes.size()));
                Map<String, Integer> cards = mainCards(DeckgenUtil.buildLDACArchetypeDeck(a, format, true));
                int missing = 0;
                for (Map.Entry<String, Integer> e : cards.entrySet()) if (!onClient.test(e.getKey())) missing += e.getValue();
                if (missing < bestMissing) {
                    bestMissing = missing;
                    best = new Choice(a.getName() + " (" + key + ")", cards);
                }
            }
            if (best != null && bestMissing <= 12) {
                if (bestMissing > 0) System.out.println("[mtga] AI deck " + best.name() + ": " + bestMissing + " cards not on the client -> basic lands");
                return new Choice(best.name(), replaceMissing(best.cards(), onClient));
            }
        } catch (Exception e) {
            System.out.println("[mtga] AI archetype deck for " + key + " failed: " + e);
        }
        Deck d = DeckgenUtil.getRandomColorDeck(format.getFilterRules().and(pc -> onClient.test(pc.getName())), true);
        return new Choice("Random " + key, mainCards(d));
    }

    private static Map<String, Integer> mainCards(Deck d) {
        Map<String, Integer> cards = new LinkedHashMap<>();
        for (Map.Entry<PaperCard, Integer> e : d.getOrCreate(DeckSection.Main)) cards.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        return cards;
    }

    /** Cards the client lacks become copies of the deck's most common basic land (Wastes if it has none). */
    private static Map<String, Integer> replaceMissing(Map<String, Integer> cards, Predicate<String> onClient) {
        Map<String, Integer> out = new LinkedHashMap<>();
        int missing = 0;
        String basic = null;
        for (Map.Entry<String, Integer> e : cards.entrySet()) {
            if (!onClient.test(e.getKey())) { missing += e.getValue(); continue; }
            out.put(e.getKey(), e.getValue());
            boolean isBasic = e.getKey().matches("Plains|Island|Swamp|Mountain|Forest");
            if (isBasic && (basic == null || e.getValue() > out.get(basic))) basic = e.getKey();
        }
        if (missing > 0) out.merge(basic == null ? "Wastes" : basic, missing, Integer::sum);
        return out;
    }

    /** Names of all precons (for logs / docs). */
    public static List<String> names() {
        File[] all = new File(ForgeConstants.QUEST_PRECON_DIR).listFiles((d, n) -> n.endsWith(".dck"));
        List<String> out = new ArrayList<>();
        if (all != null) for (File f : all) out.add(f.getName().replace(".dck", ""));
        return out;
    }
}

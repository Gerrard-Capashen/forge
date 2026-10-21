package forge.mtga;

import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
        Map<String, Integer> cards = new LinkedHashMap<>();
        for (Map.Entry<PaperCard, Integer> e : d.getOrCreate(DeckSection.Main)) cards.merge(e.getKey().getName(), e.getValue(), Integer::sum);
        String name = d.getName() == null ? f.getName().replace(".dck", "") : d.getName();
        return new Choice(name.replaceFirst("^MTGA ", ""), cards);
    }

    /** Names of all precons (for logs / docs). */
    public static List<String> names() {
        File[] all = new File(ForgeConstants.QUEST_PRECON_DIR).listFiles((d, n) -> n.endsWith(".dck"));
        List<String> out = new ArrayList<>();
        if (all != null) for (File f : all) out.add(f.getName().replace(".dck", ""));
        return out;
    }
}

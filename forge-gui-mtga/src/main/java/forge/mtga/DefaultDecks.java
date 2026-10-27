package forge.mtga;

import forge.StaticData;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The built-in decks (resources {@code default-decks/<Format>/<Name>.txt}, listed in {@code default-decks/index.txt}): the
 * player's starter decks and the pool the Bot Play AI picks from. Lists are MTGA/Moxfield exports ("4 Card Name &lt;style&gt;
 * [SET] (F)"; a blank line starts the sideboard); only the card names matter.
 */
public final class DefaultDecks {
    private DefaultDecks() { }

    /**
     * {@code main}/{@code side}: Forge card name -> quantity, in list order; {@code companion}: the companion (a list's sideboard
     * card with the Companion ability, taken out of {@code side}), or null.
     */
    public record Entry(String format, String name, Map<String, Integer> main, Map<String, Integer> side, String companion) { }

    /** Every companion card; a sideboard holding one of these in a single copy declares it as its companion. */
    private static final java.util.Set<String> COMPANIONS = java.util.Set.of("Gyruda, Doom of Depths", "Jegantha, the Wellspring", "Kaheera, the Orphanguard",
            "Keruga, the Macrovore", "Lurrus of the Dream-Den", "Lutri, the Spellchaser", "Obosh, the Preypiercer", "Umori, the Collector",
            "Yorion, Sky Nomad", "Zirda, the Dawnwaker");

    private static final Pattern LINE = Pattern.compile("^([0-9]+)[ ]+(.+)$");

    private static List<Entry> cache;

    public static synchronized List<Entry> all() {
        if (cache == null) cache = load("default-decks");
        return cache;
    }

    private static List<Entry> botCache;
    /** Combo decks stay out of the bots' pool (hidden win conditions are no fun to play against, and the AI pilots them badly). */
    private static final Pattern COMBO = Pattern.compile("Doomsday|Combo|Oath|Storm|Reanimator|Lurrus PO$");

    /**
     * The decks only the Bot Play AI plays (resources {@code bot-decks/}, same layout; refreshed by tools/fetch-goldfish-decks.mjs),
     * of the format, plus the player's starter decks of it: a wide pool, so an opponent's deck says little about what it holds.
     */
    public static synchronized List<Entry> botPool(String format) {
        if (botCache == null) botCache = load("bot-decks");
        List<Entry> out = new ArrayList<>(of(format));
        out.removeIf(e -> COMBO.matcher(e.name()).find());
        for (Entry e : botCache) if (e.format().equalsIgnoreCase(format) && out.stream().noneMatch(o -> o.name().equals(e.name()))) out.add(e);
        return out;
    }

    private static List<Entry> load(String root) {
        List<Entry> out = new ArrayList<>();
        for (String path : lines(root, "index.txt")) {
            if (path.isBlank()) continue;
            int slash = path.indexOf('/');
            String format = path.substring(0, slash), name = path.substring(slash + 1).replaceFirst("[.]txt$", "");
            Map<String, Integer> main = new LinkedHashMap<>(), side = new LinkedHashMap<>();
            boolean sideboard = false;
            for (String l : lines(root, path)) {
                if (l.isBlank()) { sideboard = main.size() > 0; continue; }
                Matcher m = LINE.matcher(l.trim());
                if (!m.matches()) continue;
                String card = m.group(2);
                card = stripTail(card);
                (sideboard ? side : main).merge(forgeName(card.trim()), Integer.parseInt(m.group(1)), Integer::sum);
            }
            String companion = side.entrySet().stream().filter(e -> e.getValue() == 1 && COMPANIONS.contains(e.getKey())).map(Map.Entry::getKey).findFirst().orElse(null);
            if (companion != null) side.remove(companion);
            out.add(new Entry(format, name, main, side, companion));
        }
        return out;
    }

    public static List<Entry> of(String format) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) if (e.format().equalsIgnoreCase(format)) out.add(e);
        return out;
    }

    /** Removes trailing "&lt;style&gt;", "[SET]" and "(F)" tokens. */
    private static String stripTail(String card) {
        String c = card.trim();
        while (c.length() > 0) {
            char last = c.charAt(c.length() - 1);
            int open = last == '>' ? c.lastIndexOf('<') : last == ']' ? c.lastIndexOf('[') : last == ')' ? c.lastIndexOf('(') : -1;
            if (open <= 0) break;
            c = c.substring(0, open).trim();
        }
        return c;
    }

    /**
     * Forge's spelling: accents as Forge writes them ("Lorien Revealed" is "Lórien Revealed"); split cards "A // B" (also written
     * "A/B"), double-faced cards and rooms are known by their front face "A" only.
     */
    private static String forgeName(String name) {
        int slash = name.indexOf('/');
        if (slash <= 0) {
            try {
                var pc = StaticData.instance().getCommonCards().getUniqueByName(name);
                return pc == null ? name : pc.getName();
            } catch (RuntimeException e) {
                return name;   // card database not loaded
            }
        }
        String front = name.substring(0, slash).trim(), back = name.substring(name.lastIndexOf('/') + 1).trim();
        try {
            var cards = StaticData.instance().getCommonCards();
            if (cards.getUniqueByName(front + " // " + back) != null) return front + " // " + back;
            if (cards.getUniqueByName(name) != null) return name;
            if (cards.getUniqueByName(front) != null) return front;
        } catch (RuntimeException e) {
            // card database not loaded (nothing to normalise against)
        }
        return name;
    }

    private static List<String> lines(String root, String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = DefaultDecks.class.getResourceAsStream("/" + root + "/" + resource)) {
            if (in == null) throw new IllegalStateException("missing resource " + root + "/" + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}

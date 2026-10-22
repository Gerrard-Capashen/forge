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

    /** {@code main}/{@code side}: Forge card name -> quantity, in list order. */
    public record Entry(String format, String name, Map<String, Integer> main, Map<String, Integer> side) { }

    private static final Pattern LINE = Pattern.compile("^([0-9]+)[ ]+(.+)$");

    private static List<Entry> cache;

    public static synchronized List<Entry> all() {
        if (cache != null) return cache;
        List<Entry> out = new ArrayList<>();
        for (String path : lines("index.txt")) {
            if (path.isBlank()) continue;
            int slash = path.indexOf('/');
            String format = path.substring(0, slash), name = path.substring(slash + 1).replaceFirst("[.]txt$", "");
            Map<String, Integer> main = new LinkedHashMap<>(), side = new LinkedHashMap<>();
            boolean sideboard = false;
            for (String l : lines(path)) {
                if (l.isBlank()) { sideboard = main.size() > 0; continue; }
                Matcher m = LINE.matcher(l.trim());
                if (!m.matches()) continue;
                String card = m.group(2);
                card = stripTail(card);
                (sideboard ? side : main).merge(forgeName(card.trim()), Integer.parseInt(m.group(1)), Integer::sum);
            }
            out.add(new Entry(format, name, main, side));
        }
        cache = out;
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

    /** Forge's spelling: split cards "A // B" (also written "A/B"), double-faced cards and rooms are known by their front face "A" only. */
    private static String forgeName(String name) {
        int slash = name.indexOf('/');
        if (slash <= 0) return name;
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

    private static List<String> lines(String resource) {
        List<String> out = new ArrayList<>();
        try (InputStream in = DefaultDecks.class.getResourceAsStream("/default-decks/" + resource)) {
            if (in == null) throw new IllegalStateException("missing resource default-decks/" + resource);
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String l; (l = r.readLine()) != null; ) out.add(l);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }
}

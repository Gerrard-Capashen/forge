package forge.mtga.art;

import forge.localinstance.properties.ForgeConstants;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Large illustrations for the quick draft event pages from mtgpics.com's art gallery. The gallery uses the same set
 * codes as Forge in its image paths ({@code pics/art/fra/129.jpg}), lists each illustration's pixel size, and the
 * originals are 1200-4000 px wide (Scryfall's art crops are only about 626x457, too soft for a full page).
 * Gentle on the site: one set-list page (cached on disk) and two pages per set, one image per set, everything cached
 * on disk, at most one request a second, only when an event's art is first shown.
 */
final class MtgPicsArt {
    private static final String BASE = "https://www.mtgpics.com/";
    private static final int MIN_WIDTH = 1600, MIN_WIDTH_FALLBACK = 1000;

    private final File dir = new File(new File(ForgeConstants.CACHE_CARD_PICS_DIR).getParentFile(), "mtgpics-events");
    private final Random rnd = new Random();
    private Map<String, Integer> setIds;
    private long lastRequest;

    MtgPicsArt() {
        dir.mkdirs();
    }

    /** The chosen illustration of a set (cached after the first time), null when the site has none usable. */
    synchronized BufferedImage image(String setCode) {
        String code = setCode.toLowerCase();
        File cached = new File(dir, code + ".jpg");
        try {
            if (!cached.isFile()) {
                Integer id = setIds().get(siteCode(code));
                if (id == null) {
                    System.out.println("[art] mtgpics: no set " + code);
                    return null;
                }
                String pick = pickIllustration(id);
                if (pick == null) return null;
                byte[] bytes = get(BASE + "pics/art/" + pick + ".jpg", true);
                if (bytes == null) return null;
                Files.write(cached.toPath(), bytes);
                System.out.println("[art] mtgpics: " + code + " -> " + pick + " (" + bytes.length + " bytes)");
            }
            return ImageIO.read(cached);
        } catch (Exception e) {
            System.err.println("[art] mtgpics " + code + ": " + e);
            return null;
        }
    }

    /** A given gallery illustration ({@code pics/art/<path>.jpg}), cached under {@code key} like a set's, null if it cannot be fetched. */
    synchronized BufferedImage illustration(String key, String path) {
        File cached = new File(dir, key.toLowerCase() + ".jpg");
        try {
            if (!cached.isFile()) {
                byte[] bytes = get(BASE + "pics/art/" + path + ".jpg", true);
                if (bytes == null) return null;
                Files.write(cached.toPath(), bytes);
                System.out.println("[art] mtgpics: " + key + " -> " + path + " (" + bytes.length + " bytes)");
            }
            return ImageIO.read(cached);
        } catch (Exception e) {
            System.err.println("[art] mtgpics " + key + ": " + e);
            return null;
        }
    }

    /** The gallery's code for a Forge set code: it names Magic 2011-2015 "11m".."15m", and has no Innistrad: Double Feature (its cards are those of the two Innistrad sets). */
    private String siteCode(String code) {
        if (code.matches("m1[1-5]")) return code.substring(1) + "m";
        if (code.equals("dbl")) return rnd.nextBoolean() ? "mid" : "vow";
        return code;
    }

    /** Set code -> the gallery's set id, from the /sets page (its logos are named after the set code). */
    private Map<String, Integer> setIds() throws Exception {
        if (setIds != null) return setIds;
        File file = new File(dir, "sets.tsv");
        Map<String, Integer> map = new HashMap<>();
        if (file.isFile()) {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                String[] p = line.split("\t");
                if (p.length == 2) map.put(p[0], Integer.parseInt(p[1]));
            }
        }
        if (map.isEmpty()) {
            String html = text(BASE + "sets");
            if (html != null) {
                Matcher m = Pattern.compile("href=set\\?set=(\\d+)>\\s*<img src=graph/sets/logos/([a-z0-9_]+)\\.png").matcher(html);
                StringBuilder sb = new StringBuilder();
                while (m.find()) {
                    map.putIfAbsent(m.group(2), Integer.parseInt(m.group(1)));
                    sb.append(m.group(2)).append('\t').append(m.group(1)).append('\n');
                }
                if (!map.isEmpty()) Files.writeString(file.toPath(), sb.toString(), StandardCharsets.UTF_8);
            }
        }
        System.out.println("[art] mtgpics: " + map.size() + " sets known");
        return setIds = map;
    }

    private record Item(String path, int w, int h) { }

    /**
     * A random large landscape-ish illustration of the set: "fra/129". The site answers some list pages with an error
     * now and then, so pages are tried in random order until a few have answered.
     */
    private String pickIllustration(int setId) throws Exception {
        List<Item> candidates = new ArrayList<>();
        int pages = 1, answered = 0;
        List<Integer> order = new ArrayList<>(List.of(0));
        for (int attempt = 0; attempt < 10 && answered < 2; attempt++) {
            int page = order.isEmpty() ? rnd.nextInt(answered == 0 ? 7 : pages) : order.remove(0);   // before any answer the page count is unknown
            String html = getOnceText(BASE + "art?set=" + setId + (page == 0 ? "" : "&pointeur=" + page * 60));
            if (html == null) continue;
            answered++;
            Matcher total = Pattern.compile("(\\d+) illustrations").matcher(html);
            if (total.find()) pages = Math.max(pages, (Integer.parseInt(total.group(1)) + 59) / 60);
            candidates.addAll(items(html));
        }
        List<Item> good = new ArrayList<>();
        for (Item i : candidates) {
            if (i.w() >= MIN_WIDTH && i.w() >= i.h() * 1.15) good.add(i);
        }
        if (good.isEmpty()) {   // older sets: settle for smaller scans
            for (Item i : candidates) {
                if (i.w() >= MIN_WIDTH_FALLBACK && i.w() >= i.h() * 1.15) good.add(i);
            }
        }
        if (good.isEmpty()) {
            System.out.println("[art] mtgpics: set " + setId + " has no large landscape art on the pages that answered (" + answered + ")");
            return null;
        }
        return good.get(rnd.nextInt(good.size())).path();
    }

    private String getOnceText(String url) {
        byte[] b = getOnce(url, false, false);
        return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
    }

    private static List<Item> items(String html) {
        List<Item> out = new ArrayList<>();
        Pattern p = Pattern.compile("url\\(pics/art_th_big/([a-z0-9_]+/[a-z0-9]+)\\.jpg\\).*?(\\d+) x (\\d+)", Pattern.DOTALL);
        for (String block : html.split("float:left;position:relative;background: ")) {
            Matcher m = p.matcher(block);
            if (m.find()) out.add(new Item(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        }
        return out;
    }

    private String text(String url) {
        byte[] b = get(url, false);
        return b == null ? null : new String(b, StandardCharsets.ISO_8859_1);
    }

    /** One polite request (retried twice on an error answer): identifies itself, at most one a second. */
    private byte[] get(String url, boolean image) {
        for (int attempt = 0; attempt < 3; attempt++) {
            byte[] b = getOnce(url, image, attempt == 2);
            if (b != null) return b;
            try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
        }
        return null;
    }

    private byte[] getOnce(String url, boolean image, boolean report) {
        try {
            long wait = lastRequest + 1000 - System.currentTimeMillis();
            if (wait > 0) Thread.sleep(wait);
            lastRequest = System.currentTimeMillis();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; mtga-forge-local-art; personal use)");
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            int code = c.getResponseCode();
            String type = c.getContentType();
            if (code != HttpURLConnection.HTTP_OK || (image && (type == null || !type.startsWith("image/")))) {
                if (report) System.err.println("[art] mtgpics HTTP " + code + " (" + type + ") for " + url);
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (Exception e) {
            System.err.println("[art] mtgpics " + url + ": " + e);
            return null;
        }
    }
}

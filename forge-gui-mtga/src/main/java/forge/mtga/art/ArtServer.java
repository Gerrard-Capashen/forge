package forge.mtga.art;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import forge.StaticData;
import forge.item.PaperCard;
import forge.mtga.forge.CardDb;

import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Card art for the client copy on loopback: {@code GET /art/<artId>} answers the Scryfall art crop (JPEG) of a card
 * CardDbBuilder added (art id 9,000,000+, no art bundle in the client), fetched on first request through Forge's
 * image fetcher and cached on disk. The client process itself stays without internet access; this process is the only
 * one that talks to Scryfall. 404 when the card has no art (unknown id, no URL, every download failed).
 */
public final class ArtServer {
    private static final int MIN_ART_ID = 9_000_000;
    private static final long WAIT_SECONDS = 60;

    private final int port;
    private final Map<Integer, CardDb.ArtRef> refs;
    private final ArtFetcher fetcher = new ArtFetcher();
    private final MtgPicsArt mtgpics = new MtgPicsArt();
    private final java.util.Map<String, com.sun.net.httpserver.HttpHandler> extra = new java.util.LinkedHashMap<>();

    /** Another loopback service on this HTTP server (before {@link #start}), e.g. the friends endpoints under {@code /social/}. */
    public ArtServer addContext(String path, com.sun.net.httpserver.HttpHandler handler) {
        extra.put(path, handler);
        return this;
    }

    public ArtServer(CardDb db, int port) throws Exception {
        this.port = port;
        this.refs = db.generatedArt(MIN_ART_ID);
    }

    public void start() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        http.createContext("/art/", this::handle);
        http.createContext("/event/", this::handleEvent);
        extra.forEach(http::createContext);
        http.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "art-http");
            t.setDaemon(true);
            return t;
        }));
        http.start();
        System.out.println("[mtga] Art server listening on 127.0.0.1:" + port + " (" + refs.size() + " generated art ids)");
    }

    private void handle(HttpExchange ex) {
        try (ex) {
            File file = null;
            String id = ex.getRequestURI().getPath().substring("/art/".length());
            CardDb.ArtRef ref = id.matches("\\d{1,9}") ? refs.get(Integer.parseInt(id)) : null;
            PaperCard pc = ref == null ? null : printing(ref);
            if (pc != null) {
                try {
                    file = fetcher.artCrop(pc, ref.back()).get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.err.println("[art] " + id + " (" + pc + "): " + e);
                }
            }
            if (file == null || !file.isFile()) {
                System.out.println("[mtga] art " + id + " not available (404)");
                ex.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = Files.readAllBytes(file.toPath());
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, bytes.length);
            System.out.println("[mtga] art " + id + " served (" + bytes.length + " bytes)");
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            System.err.println("[art] request failed: " + e);
        }
    }

    private final Map<String, byte[]> eventArt = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code GET /event/<SET>}: a 640x360 JPEG crop of a showcase card (a mythic, else a rare) of a Forge set, for its quick draft event. */
    private void handleEvent(HttpExchange ex) {
        try (ex) {
            String set = ex.getRequestURI().getPath().substring("/event/".length());
            byte[] bytes = set.matches("[A-Za-z0-9]{2,8}(-bg)?") ? eventArt.computeIfAbsent(set.toUpperCase(), this::buildEventArt) : null;
            if (bytes == null || bytes.length == 0) {
                System.out.println("[mtga] event art " + set + " not available (404)");
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "image/jpeg");
            ex.sendResponseHeaders(200, bytes.length);
            System.out.println("[mtga] event art " + set + " served (" + bytes.length + " bytes)");
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        } catch (Exception e) {
            System.err.println("[art] event request failed: " + e);
        }
    }

    private byte[] buildEventArt(String key) {
        boolean background = key.endsWith("-BG");
        String set = background ? key.substring(0, key.length() - 3) : key;
        try {
            // the page background: a large illustration from the mtgpics gallery (full-page quality); tiles use the Scryfall crop
            if (background) {
                java.awt.image.BufferedImage big = mtgpics.image(set);
                if (big != null) return cover(big, 1920, 1080, 0.18);
                return new byte[0];   // no usable large art: the client keeps its own default background
            }
            forge.card.CardEdition ed = StaticData.instance().getEditions().get(set);
            if (ed == null) return new byte[0];
            var cards = StaticData.instance().getCommonCards();
            java.util.List<PaperCard> mythics = new java.util.ArrayList<>(), rares = new java.util.ArrayList<>();
            for (forge.card.CardEdition.EditionEntry e : ed.getAllCardsInSet()) {
                if (e.rarity() != forge.card.CardRarity.MythicRare && e.rarity() != forge.card.CardRarity.Rare) continue;
                PaperCard pc = cards.getCard(e.name(), ed.getCode());
                if (pc == null) continue;
                (e.rarity() == forge.card.CardRarity.MythicRare ? mythics : rares).add(pc);
            }
            java.util.List<PaperCard> pool = mythics.isEmpty() ? rares : mythics;
            if (pool.isEmpty()) return new byte[0];
            // stable choice per set, falling back through the list when a download fails
            int start = Math.floorMod(set.hashCode(), pool.size());
            for (int i = 0; i < Math.min(pool.size(), 4); i++) {
                PaperCard pc = pool.get((start + i) % pool.size());
                File file;
                try {
                    file = fetcher.artCrop(pc, false).get(WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    System.err.println("[art] event " + set + " (" + pc + "): " + e);
                    continue;
                }
                if (file == null || !file.isFile()) continue;
                java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(file);
                if (src == null) continue;
                System.out.println("[mtga] event art " + set + ": " + pc.getName());
                return background ? backdrop(src, 1920, 1080) : cover(src, 640, 360, 0);
            }
        } catch (Exception e) {
            System.err.println("[art] event art " + set + ": " + e);
        }
        return new byte[0];
    }

    /** Full-page background from a low-resolution art crop: the whole art stretched over the frame (cover), blurred and slightly darkened. */
    private static byte[] backdrop(java.awt.image.BufferedImage src, int w, int h) throws java.io.IOException {
        // blur: shrink, then stretch back to the full frame with smooth interpolation
        int sw = 128, sh = Math.max(1, Math.round(sw * src.getHeight() / (float) src.getWidth()));
        java.awt.image.BufferedImage small = new java.awt.image.BufferedImage(sw, sh, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D sg = small.createGraphics();
        sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        sg.drawImage(src, 0, 0, sw, sh, null);
        sg.dispose();
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        double scale = Math.max(w / (double) sw, h / (double) sh);
        int bw = (int) Math.ceil(sw * scale), bh = (int) Math.ceil(sh * scale);
        g.drawImage(small, (w - bw) / 2, (h - bh) / 2, bw, bh, null);
        g.setColor(new java.awt.Color(0, 0, 0, 70));
        g.fillRect(0, 0, w, h);
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "jpg", bos);
        return bos.toByteArray();
    }

    /** Scale to cover w x h and center-crop, as JPEG. */
    private static byte[] cover(java.awt.image.BufferedImage src, int w, int h, double darken) throws java.io.IOException {
        double scale = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
        int sw = (int) Math.ceil(src.getWidth() * scale), sh = (int) Math.ceil(src.getHeight() * scale);
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = out.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        // large originals: halve in steps first so the final scale stays smooth
        java.awt.image.BufferedImage from = src;
        while (from.getWidth() / 2 >= sw && from.getHeight() / 2 >= sh) {
            java.awt.image.BufferedImage half = new java.awt.image.BufferedImage(from.getWidth() / 2, from.getHeight() / 2, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(from, 0, 0, half.getWidth(), half.getHeight(), null);
            hg.dispose();
            from = half;
        }
        g.drawImage(from, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
        if (darken > 0) {
            g.setColor(new java.awt.Color(0, 0, 0, (int) Math.round(darken * 255)));
            g.fillRect(0, 0, w, h);
        }
        g.dispose();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(out, "jpg", bos);
        return bos.toByteArray();
    }

    /** The exact printing the card DB row names (Forge set + collector number), else any printing of that set. */
    private static PaperCard printing(CardDb.ArtRef ref) {
        var cards = StaticData.instance().getCommonCards();
        PaperCard pc = ref.collectorNumber() == null || ref.collectorNumber().isEmpty() ? null
                : cards.getCard(ref.name(), ref.set(), ref.collectorNumber());
        return pc != null ? pc : cards.getCard(ref.name(), ref.set());
    }
}

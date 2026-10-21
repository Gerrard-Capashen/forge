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

    public ArtServer(CardDb db, int port) throws Exception {
        this.port = port;
        this.refs = db.generatedArt(MIN_ART_ID);
    }

    public void start() throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 16);
        http.createContext("/art/", this::handle);
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

    /** The exact printing the card DB row names (Forge set + collector number), else any printing of that set. */
    private static PaperCard printing(CardDb.ArtRef ref) {
        var cards = StaticData.instance().getCommonCards();
        PaperCard pc = ref.collectorNumber() == null || ref.collectorNumber().isEmpty() ? null
                : cards.getCard(ref.name(), ref.set(), ref.collectorNumber());
        return pc != null ? pc : cards.getCard(ref.name(), ref.set());
    }
}

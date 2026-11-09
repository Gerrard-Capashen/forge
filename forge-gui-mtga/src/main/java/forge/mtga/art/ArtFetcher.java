package forge.mtga.art;

import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.ImageFetcher;
import forge.util.ScryfallRateLimiter;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Forge's own image fetcher without a UI: Forge picks the Scryfall URLs (set code mapping, faces, CDN cache), skips
 * images already in its cache and paces api.scryfall.com; this class only downloads. Art crops land in Forge's normal
 * card image cache ({@code <set>/<name>.artcrop.jpg}), so a Forge desktop install shares them.
 * ImageFetcher expects to run on one UI thread: every call into it runs on a single "art-edt" thread.
 */
final class ArtFetcher extends ImageFetcher {
    private final ExecutorService edt = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "art-edt");
        t.setDaemon(true);
        return t;
    });
    /** Cache file path -> result (the file, or null when every URL failed). Failures stay cached until restart. */
    private final Map<String, CompletableFuture<File>> fetches = new ConcurrentHashMap<>();
    private boolean taskStarted;   // art-edt only

    ArtFetcher() {
        // In memory only (never saved): the art crop format and online fetching, whatever the user's Forge prefs say.
        FModel.getPreferences().setPref(FPref.UI_ENABLE_ONLINE_IMAGE_FETCHER, true);
        FModel.getPreferences().setPref(FPref.UI_DISABLE_CARD_IMAGES, false);
        FModel.getPreferences().setPref(FPref.UI_CARD_ART_FORMAT, "Crop");
    }

    /** The art crop of a printing (back face for transform/flip backs): from the cache, else downloaded once. */
    CompletableFuture<File> artCrop(PaperCard pc, boolean back) {
        String key = back ? pc.getCardAltImageKey() : pc.getCardImageKey();
        File dest = new File(ForgeConstants.CACHE_CARD_PICS_DIR, key.replace(".full", ".artcrop") + ".jpg");
        if (dest.isFile()) return CompletableFuture.completedFuture(dest);
        CompletableFuture<File> result = new CompletableFuture<>();
        CompletableFuture<File> known = fetches.putIfAbsent(dest.getAbsolutePath(), result);
        if (known != null) return known;
        edt.execute(() -> {
            taskStarted = false;
            try {
                fetchImage(pc.getImageKey(back), () -> { });
            } catch (RuntimeException e) {
                System.err.println("[art] fetchImage failed for " + pc + ": " + e);
            }
            // fetchImage returns without a download when it has no URL for the card (or the file appeared meanwhile)
            if (!taskStarted) result.complete(dest.isFile() ? dest : null);
        });
        return result;
    }

    /** A fixed image URL (art Forge has no printing for) saved at {@code dest}: from the cache, else downloaded once; null when it fails. */
    File direct(String url, File dest) {
        if (dest.isFile()) return dest;
        synchronized (this) {
            return dest.isFile() || download(url, dest) ? dest : null;
        }
    }

    @Override
    protected Runnable getDownloadTask(String[] urls, String destPath, Runnable notifyObservers) {
        CompletableFuture<File> result = fetches.get(destPath);
        if (result == null) {
            System.err.println("[art] unexpected destination " + destPath);
            return () -> { };
        }
        taskStarted = true;
        return () -> {
            File dest = new File(destPath);
            for (String url : urls) {
                if (download(url, dest)) {
                    System.out.println("[art] saved " + dest);
                    edt.execute(notifyObservers);
                    result.complete(dest);
                    return;
                }
            }
            System.err.println("[art] no art for " + dest.getName());
            result.complete(null);
        };
    }

    /** One URL to {@code dest} (via a temporary file so nothing reads a partial image), with Forge's Scryfall etiquette. */
    private static boolean download(String url, File dest) {
        if (ScryfallRateLimiter.shouldSkip(url)) return false;
        try {
            ScryfallRateLimiter.acquire(url);
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("User-Agent", forge.gui.GuiBase.getInterface().getUserAgent());
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            int code = c.getResponseCode();
            String type = c.getContentType();
            if (code != HttpURLConnection.HTTP_OK || type == null || !type.startsWith("image/")) {
                System.err.println("[art] HTTP " + code + " (" + type + ") for " + url);
                ScryfallRateLimiter.noteIfRateLimited(code, url, c.getHeaderField("Retry-After"));
                c.disconnect();
                return false;
            }
            dest.getParentFile().mkdirs();
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream()) {
                Files.copy(in, tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (Exception e) {
            System.err.println("[art] " + url + ": " + e);
            return false;
        }
    }
}

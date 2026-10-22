package forge.mtga.social;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import forge.mtga.profile.FileProfileStore;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;

/**
 * Two players' social clients against a coordinator (default the deployed one, {@code -Dcheck.coordinator=http://127.0.0.1:8787}
 * for a local `npm run dev`): invite through the loopback endpoints, accept, presence, remove, block. Players are called
 * "zztest ...". Prints "SOCIAL CHECK OK" or the failures; exit code 1 on failure.
 */
public final class SocialCheck {
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + what);
        if (!ok) failures.add(what);
    }

    private static void delete(File dir) {
        File[] kids = dir.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        dir.delete();
    }

    private record Player(File dir, FileProfileStore store, SocialClient client, String full, String id, HttpServer http, int port) { }

    private static Player player(String url, String label, String run) throws Exception {
        File dir = Files.createTempDirectory("social-check-" + label).toFile();
        FileProfileStore store = new FileProfileStore(dir);
        store.update(p -> p.displayName = "zztest " + label + " " + run);
        Accounts accounts = new Accounts(url, new IdentityStore(dir));
        Accounts.sync(store, accounts);
        SocialClient client = new SocialClient(accounts, store);
        client.start();
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
        http.createContext("/social/", new SocialEndpoints(client));
        http.start();
        return new Player(dir, store, client, store.load().clientName(), store.load().playerId, http, http.getAddress().getPort());
    }

    private static JsonObject http(Player p, String method, String path, String body, boolean header) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create("http://127.0.0.1:" + p.port + path).toURL().openConnection();
        c.setRequestMethod(method);
        if (header) c.setRequestProperty("X-Forge-Social", "1");
        if (body != null) {
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        String text = new String((status >= 400 ? c.getErrorStream() : c.getInputStream()).readAllBytes(), StandardCharsets.UTF_8);
        JsonObject o = text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
        o.addProperty("_http", status);
        return o;
    }

    /** Polls the player's snapshot until the predicate holds (the client's long poll, as the bridge does). */
    private static JsonObject await(Player p, Predicate<JsonObject> ok, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        long since = 0;
        JsonObject s;
        for (;;) {
            s = p.client.awaitChange(since, 500);
            if (ok.test(s) || System.currentTimeMillis() > end) return ok.test(s) ? s : null;
            since = s.get("version").getAsLong();
        }
    }

    private static boolean has(JsonObject s, String list, String fullName, String status) {
        for (JsonElement e : s.getAsJsonArray(list)) {
            JsonObject o = e.getAsJsonObject();
            if (o.get("fullName").getAsString().equals(fullName) && (status == null || status.equals(o.get("status").getAsString()))) return true;
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        String url = System.getProperty("check.coordinator", "https://mm.mtga-forge.workers.dev");
        String run = Integer.toHexString(new Random().nextInt(0xffffff));
        Player a = player(url, "ja", run), b = player(url, "jb", run);
        try {
            check(await(a, s -> s.get("connected").getAsBoolean(), 15_000) != null && await(b, s -> s.get("connected").getAsBoolean(), 15_000) != null, "both players signed in (session open)");
            check(a.client.snapshot().getAsJsonObject("self").get("fullName").getAsString().equals(a.full), "snapshot: self is " + a.full);

            // the loopback endpoints refuse a request without the header (a web page cannot send it)
            check(http(a, "POST", "/social/invite", "{\"full\":\"" + b.full + "\"}", false).get("_http").getAsInt() == 403, "POST without X-Forge-Social: 403");

            JsonObject r = http(a, "POST", "/social/invite", "{\"full\":\"" + b.full + "\"}", true);
            check(r.get("_http").getAsInt() == 200 && !r.has("error"), "A invites B through /social/invite");
            check(await(b, s -> has(s, "incoming", a.full, null), 8_000) != null, "B's client sees the incoming invite (pushed)");
            check(has(a.client.snapshot(), "outgoing", b.full, null), "A's client lists the outgoing invite");
            r = http(a, "POST", "/social/invite", "{\"full\":\"" + b.full + "\"}", true);
            check(r.has("error") && r.get("status").getAsInt() == 409, "inviting again: the coordinator's reason comes back (" + (r.has("error") ? r.get("error").getAsString() : "?") + ")");

            http(b, "POST", "/social/accept", "{\"playerId\":\"" + a.id + "\"}", true);
            check(await(a, s -> has(s, "friends", b.full, "Available"), 8_000) != null, "A sees B as a friend, Available");
            check(await(b, s -> has(s, "friends", a.full, "Available"), 8_000) != null, "B sees A as a friend, Available");

            http(b, "POST", "/social/status", "{\"status\":\"Busy\"}", true);
            check(await(a, s -> has(s, "friends", b.full, "Busy"), 8_000) != null, "B goes Busy: A's list shows it");
            http(b, "POST", "/social/status", "{\"status\":\"Offline\"}", true);
            check(await(a, s -> has(s, "friends", b.full, "Offline"), 8_000) != null, "B sets Offline: A's list shows Offline");
            http(b, "POST", "/social/status", "{\"status\":\"Available\"}", true);
            check(await(a, s -> has(s, "friends", b.full, "Available"), 8_000) != null, "B back Available");

            r = http(a, "POST", "/social/resolve", "{\"full\":\"" + b.full + "\"}", true);
            check(b.id.equals(r.has("playerId") ? r.get("playerId").getAsString() : ""), "resolve Name#tag -> player id");

            // the long poll returns at once when the version is already newer, and waits when not
            long v = a.client.version();
            long t0 = System.currentTimeMillis();
            a.client.awaitChange(v, 700);
            check(System.currentTimeMillis() - t0 >= 600, "long poll waits for a change (waited " + (System.currentTimeMillis() - t0) + " ms)");

            b.client.stop();
            check(await(a, s -> has(s, "friends", b.full, "Offline"), 10_000) != null, "B's client stops: A sees B Offline");

            http(a, "POST", "/social/remove", "{\"playerId\":\"" + b.id + "\"}", true);
            check(await(a, s -> s.getAsJsonArray("friends").size() == 0, 8_000) != null, "A removes B: the list is empty");
            http(a, "POST", "/social/block", "{\"playerId\":\"" + b.id + "\"}", true);
            check(await(a, s -> has(s, "blocks", b.full, null), 8_000) != null, "A blocks B: listed in the blocks");
            http(a, "POST", "/social/unblock", "{\"playerId\":\"" + b.id + "\"}", true);
            check(await(a, s -> s.getAsJsonArray("blocks").size() == 0, 8_000) != null, "A unblocks B");
        } finally {
            a.client.stop();
            b.client.stop();
            a.http.stop(0);
            b.http.stop(0);
            delete(a.dir);
            delete(b.dir);
        }
        System.out.println(failures.isEmpty() ? "SOCIAL CHECK OK" : "SOCIAL CHECK FAILED: " + failures);
        System.exit(failures.isEmpty() ? 0 : 1);
    }
}

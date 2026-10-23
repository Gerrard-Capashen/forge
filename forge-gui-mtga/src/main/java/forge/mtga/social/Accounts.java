package forge.mtga.social;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.FileProfileStore;
import forge.mtga.profile.Profile;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client of the coordinator's account endpoints (coordinator/src/accounts.ts): registers this install's player id with its
 * device key and keeps the public name "Name#12345" in step with the local profile. The coordinator is the authority for the
 * tag; offline the local profile keeps working and the registration is retried later.
 */
public final class Accounts {
    private static final Gson GSON = new Gson();

    /** What the coordinator knows about a player. */
    public record Account(String playerId, String name, String tag, boolean dev) {
        public String fullName() { return name + "#" + tag; }
    }

    /** The coordinator answered with an error status; {@code getMessage()} reads "/path: 409 reason". */
    public static final class ApiException extends IOException {
        public final int status;
        public final String reason;

        ApiException(String path, int status, String reason) {
            super(path + ": " + status + " " + reason);
            this.status = status;
            this.reason = reason;
        }
    }

    private final String base;
    private final IdentityStore identity;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** {@code coordinator}: its ws(s):// or http(s):// address. */
    public Accounts(String coordinator, IdentityStore identity) {
        String b = coordinator.trim().replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.identity = identity;
    }

    /** A name the coordinator accepts (3-24 letters, digits, space, _ or -) made from whatever the player typed. */
    public static String registrable(String name) {
        String s = (name == null ? "" : name).replaceAll("[^\\p{L}\\p{N}_\\- ]", "").trim().replaceAll(" {2,}", " ");
        if (s.length() > 24) s = s.substring(0, 24).trim();
        return s.length() < 3 ? "Player " + s : s;
    }

    /**
     * Registers the profile's player id (idempotent: an id already registered with this key just answers its record), then renames
     * when the profile's name differs from the registered one. A returning player first asks for the tag the profile already shows.
     */
    public Account register(Profile p) throws IOException {
        String name = registrable(p.displayName);
        String nonce = nonce();
        JsonObject body = new JsonObject();
        body.addProperty("playerId", p.playerId);
        body.addProperty("publicKey", identity.publicKey());
        body.addProperty("name", name);
        if (p.tag != null && p.tag.matches("\\d{5}")) body.addProperty("tag", p.tag);
        body.addProperty("nonce", nonce);
        body.addProperty("sig", identity.sign("register|" + p.playerId + "|" + nonce + "|" + name));
        Account a = account(call("POST", "/register", body, null));
        if (!a.name().equals(name)) a = rename(p.playerId, name);
        return a;
    }

    /** Signs in (a nonce, signed) and changes the public name; the coordinator gives a new tag. */
    public Account rename(String playerId, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name);
        return account(call("PATCH", "/me", body, login(playerId)));
    }

    /** A session token (24 h) for the REST calls. */
    public String login(String playerId) throws IOException {
        String nonce = nonce();
        JsonObject body = new JsonObject();
        body.addProperty("playerId", playerId);
        body.addProperty("nonce", nonce);
        body.addProperty("sig", identity.sign("login|" + playerId + "|" + nonce));
        return call("POST", "/auth/login", body, null).get("token").getAsString();
    }

    private String nonce() throws IOException { return call("POST", "/auth/challenge", null, null).get("nonce").getAsString(); }

    private static Account account(JsonObject o) {
        return new Account(o.get("playerId").getAsString(), o.get("name").getAsString(), o.get("tag").getAsString(),
                o.has("dev") && o.get("dev").getAsBoolean());
    }

    /** The coordinator's ws(s):// address of this server (for the session WebSocket). */
    public String wsBase() { return base.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://"); }

    /** One REST call with an optional bearer token; the answer's JSON object, or an {@link ApiException}. */
    public JsonObject call(String method, String path, JsonObject body, String token) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8))
                .header("content-type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (token != null) b.header("authorization", "Bearer " + token);
        HttpResponse<String> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        } catch (IOException | RuntimeException e) {
            throw new IOException(path + ": cannot reach the coordinator (" + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()) + ")", e);
        }
        JsonObject o;
        try {
            o = r.body().isBlank() ? new JsonObject() : JsonParser.parseString(r.body()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException(path + ": " + r.statusCode() + " (not a coordinator answer)");
        }
        if (r.statusCode() / 100 != 2) throw new ApiException(path, r.statusCode(), o.has("error") ? o.get("error").getAsString() : r.body());
        return o;
    }

    /**
     * At server start: registers the profile (a few seconds at most) and stores the name and tag the coordinator gave in
     * {@code profile.json}, which the client reads at start. When the coordinator cannot be reached the local profile is used
     * and a background thread keeps trying (the tag then applies from the next start).
     */
    public static void sync(FileProfileStore store, Accounts accounts) {
        if (!trySync(store, accounts)) {
            Thread t = new Thread(() -> {
                for (long wait : new long[] {30_000, 120_000, 600_000, 1_800_000}) {
                    try { Thread.sleep(wait); } catch (InterruptedException e) { return; }
                    if (trySync(store, accounts)) return;
                }
            }, "account-sync");
            t.setDaemon(true);
            t.start();
        }
    }

    private static boolean trySync(FileProfileStore store, Accounts accounts) {
        Profile p = store.load();
        try {
            Account a = accounts.register(p);
            if (a.dev() != p.wotc) store.update(q -> q.wotc = a.dev());
            if (!a.name().equals(p.displayName) || !a.tag().equals(p.tag)) {
                String shown = a.name();
                store.update(q -> { q.displayName = shown; q.tag = a.tag(); });
            }
            System.out.println("[mtga] account: " + a.fullName() + " (" + a.playerId() + ")");
            return true;
        } catch (IOException e) {
            System.out.println("[mtga] account: not registered yet (" + e.getMessage() + "); using the local profile " + p.clientName());
            return false;
        }
    }
}

package forge.mtga.social;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Loopback JSON endpoints the bridge's CoordinatorSocialManager uses (served by the art server's HTTP server, {@code /social/}):
 * <pre>
 *   GET  /social/state?since=N&amp;wait=MS   the {@link SocialClient#snapshot()} once its version is above N (long poll, at most 30 s)
 *   POST /social/invite|block {full|playerId}   /social/accept|decline|revoke|remove|unblock {playerId}   /social/status {status}
 *   POST /social/resolve {full}              -> the player behind "Name#12345"
 * </pre>
 * Every POST must carry the header {@code X-Forge-Social: 1}: a web page in the player's browser can send simple cross-site
 * requests to 127.0.0.1, but not ones with a custom header (the browser would ask first, and we never answer that).
 */
public final class SocialEndpoints implements HttpHandler {
    private final SocialClient social;

    public SocialEndpoints(SocialClient social) { this.social = social; }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try (ex) {
            String action = ex.getRequestURI().getPath().substring("/social/".length());
            if ("GET".equals(ex.getRequestMethod()) && action.equals("state")) {
                long since = number(ex.getRequestURI().getQuery(), "since", 0);
                long wait = Math.min(number(ex.getRequestURI().getQuery(), "wait", 0), 30_000);
                reply(ex, 200, social.awaitChange(since, wait));
                return;
            }
            if (!"POST".equals(ex.getRequestMethod()) || !"1".equals(ex.getRequestHeaders().getFirst("X-Forge-Social"))) {
                reply(ex, 403, error("forbidden", 403));
                return;
            }
            JsonObject b = JsonParser.parseString(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            String full = str(b, "full"), id = str(b, "playerId");
            JsonObject answer = new JsonObject();
            try {
                switch (action) {
                    case "invite" -> answer = social.invite(full, id);
                    case "block" -> answer = social.block(full, id);
                    case "accept" -> social.accept(id);
                    case "decline" -> social.decline(id);
                    case "revoke" -> social.revoke(id);
                    case "remove" -> social.remove(id);
                    case "unblock" -> social.unblock(id);
                    case "status" -> social.setStatus(str(b, "status"));
                    case "resolve" -> answer = social.resolve(full);
                    default -> {
                        reply(ex, 404, error("unknown action " + action, 404));
                        return;
                    }
                }
                reply(ex, 200, answer);
            } catch (Accounts.ApiException e) {
                reply(ex, 200, error(e.reason, e.status));                      // the coordinator said no: the UI shows the reason
            } catch (IOException e) {
                reply(ex, 200, error("matchmaking server unreachable", 503));
            }
        } catch (InterruptedException | RuntimeException e) {
            System.out.println("[mtga] social endpoint failed: " + e);
        }
    }

    private static JsonObject error(String reason, int status) {
        JsonObject o = new JsonObject();
        o.addProperty("error", reason);
        o.addProperty("status", status);
        return o;
    }

    private static void reply(HttpExchange ex, int status, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }

    private static long number(String query, String key, long def) {
        if (query == null) return def;
        for (String kv : query.split("&")) {
            if (kv.startsWith(key + "=")) {
                try { return Long.parseLong(kv.substring(key.length() + 1)); } catch (NumberFormatException e) { return def; }
            }
        }
        return def;
    }
}

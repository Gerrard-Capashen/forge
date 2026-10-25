package forge.mtga.peer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Client of the matchmaking coordinator (coordinator/, a Cloudflare Worker): waits in a queue until a second player of the
 * same queue / card database / protocol (and friend code) turns up, then both open the match's relay room as {@link PeerLink}s.
 * The player who waited longest is the host and runs the game in their engine.
 */
public final class Coordinator {
    private static final ScheduledExecutorService KEEPALIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "coordinator-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** {@code role} = "host" | "guest"; {@code token} opens the room as that role. */
    public record Paired(String base, String role, String match, String token, String opponent) {
        public boolean host() { return "host".equals(role); }
    }

    /** A place in the queue: {@link #result} completes with the pairing (or exceptionally), {@link #cancel} leaves the queue. */
    public static final class Ticket {
        private final CompletableFuture<Paired> result = new CompletableFuture<>();
        private final CompletableFuture<Void> opened = new CompletableFuture<>();
        private volatile WebSocket socket;
        private volatile java.util.concurrent.ScheduledFuture<?> ping;

        public CompletableFuture<Paired> result() { return result; }

        /** Completes when the coordinator accepted the queue socket (exceptionally when it cannot be reached). */
        public CompletableFuture<Void> opened() { return opened; }

        public void cancel() {
            result.cancel(false);
            close();
        }

        private void close() {
            java.util.concurrent.ScheduledFuture<?> p = ping;
            if (p != null) p.cancel(false);
            WebSocket s = socket;
            if (s != null) s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    private Coordinator() { }

    /** {@code base} is the coordinator's ws(s):// address; {@code code} (may be empty) restricts the pairing to players using the same friend code. */
    public static Ticket queue(String base, String queue, String fingerprint, int protocol, String playerId, String name, String code) {
        return queue(base, queue, fingerprint, protocol, playerId, name, code, null);
    }

    /**
     * {@code session} (may be null) = the coordinator session token, sent as an Authorization header (never in the URL): ranked
     * queues need it, the coordinator then pairs the account rather than the {@code playerId} the URL claims.
     */
    public static Ticket queue(String base, String queue, String fingerprint, int protocol, String playerId, String name, String code, String session) {
        Ticket t = new Ticket();
        String url = trim(base) + "/queue?q=" + enc(queue) + "&fp=" + enc(fingerprint) + "&v=" + protocol
                + "&id=" + enc(playerId) + "&name=" + enc(name) + (code == null || code.isBlank() ? "" : "&code=" + enc(code));
        WebSocket.Builder builder = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (session != null) builder.header("Authorization", "Bearer " + session);
        builder.buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket w) {
                        t.socket = w;
                        t.ping = KEEPALIVE.scheduleAtFixedRate(() -> w.sendText("ping", true), 20, 20, TimeUnit.SECONDS);
                        t.opened.complete(null);
                        System.out.println("[mtga] coordinator: waiting in " + queue);
                        w.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        text.append(data);
                        if (last) {
                            String msg = text.toString();
                            text.setLength(0);
                            if (!"pong".equals(msg)) {
                                try {
                                    JsonObject o = JsonParser.parseString(msg).getAsJsonObject();
                                    if ("paired".equals(o.get("t").getAsString())) {
                                        t.result.complete(new Paired(trim(base), o.get("role").getAsString(), o.get("match").getAsString(),
                                                o.get("token").getAsString(), o.get("opp").getAsString()));
                                        t.close();
                                    }
                                } catch (RuntimeException e) {
                                    t.result.completeExceptionally(new IOException("unexpected coordinator message: " + msg));
                                }
                            }
                        }
                        w.request(1);
                        return null;
                    }

                    @Override
                    public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                        t.result.completeExceptionally(new IOException("the coordinator closed the queue (" + code + " " + reason + ")"));
                        t.close();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket w, Throwable error) {
                        t.opened.completeExceptionally(error);
                        t.result.completeExceptionally(new IOException("coordinator: " + error.getMessage(), error));
                        t.close();
                    }
                }).exceptionally(e -> {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    t.opened.completeExceptionally(c);
                    t.result.completeExceptionally(new IOException("coordinator unreachable: " + c.getMessage(), c));
                    return null;
                });
        return t;
    }

    /** Joins the match's relay room as the role the coordinator gave. */
    public static PeerLink openRoom(Paired p) throws IOException {
        return PeerLink.overWebSocket(URI.create(p.base() + "/match/" + p.match() + "?role=" + p.role() + "&token=" + p.token()), 10_000);
    }

    private static String trim(String base) { return base.endsWith("/") ? base.substring(0, base.length() - 1) : base; }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}

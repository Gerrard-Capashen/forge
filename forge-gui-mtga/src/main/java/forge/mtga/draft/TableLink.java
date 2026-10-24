package forge.mtga.draft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The draft table over the coordinator (coordinator/src/tables.ts): the table queue ({@link #queue}) and the table's
 * relay room ({@link #open}). Frames are [type][slot][UTF-8 JSON]: from a guest the slot is the sender's (the room sets
 * it), from the host the target slot or {@link #EVERYONE}. Slot 0 is the host.
 * <p>
 * Host and guests speak only these frames, so a server-run table (a Durable Object) can later take the host's place
 * without the guests changing.
 */
public final class TableLink {
    /** Version of the frames below: players with another version never meet in the queue. */
    public static final String VERSION = "t1";

    /** G->H {v, fp, spec, profile, rejoin}: first frame of a guest (again after a reconnect, with rejoin = true). */
    public static final int HELLO = 1;
    /** H->G {draftId, seat, players:[{name, avatar}], readySecs}: the guest's seat; the ready check starts. */
    public static final int SEATED = 2;
    /** H->* {n, of}: seats ready (bots included). */
    public static final int READY_COUNT = 3;
    /** G->H {}: the guest pressed Ready. */
    public static final int READY = 4;
    /** H->G {why}: the table is cancelled (someone was not ready, a mismatch): queue again. */
    public static final int CLOSE = 5;
    /** H->G {cards, pack, pick, secs, dir}: a pack to pick from (GrpIds; dir "POS" | "NEG"). */
    public static final int PACK = 6;
    /** G->H {n, grp}: the guest's pick. */
    public static final int PICK = 7;
    /** H->G {n, ok}: the answer to PICK n. */
    public static final int PICKED = 8;
    /** G->H {grp}: the card the timer takes (0 clears). */
    public static final int RESERVE = 9;
    /** H->G {grp}: a card was taken for the guest (timer, away, nothing it could see). */
    public static final int AUTO = 10;
    /** H->* {q:[8]}: packs waiting per seat. */
    public static final int QUEUES = 11;
    /** H->G {pool}: the guest's last pick is made; its pool (GrpIds). */
    public static final int DONE = 12;
    /** H->* {botDecks}: every seat is done; the bots' decks (DraftEvents.decksJson). */
    public static final int OVER = 13;
    /** H->G {draftId, seat, players, picks, done, pool}: everything a reconnecting guest needs. */
    public static final int STATE = 14;
    /** both {}: leaving. */
    public static final int BYE = 15;

    public static final int EVERYONE = 0xff;

    private static final ScheduledExecutorService KEEPALIVE = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "table-keepalive");
        t.setDaemon(true);
        return t;
    });

    /** What the queue gave this player: {@code slot} 0 = host. */
    public record Seated(String base, String table, int slot, String token, int people, List<String> names) {
        public boolean host() { return slot == 0; }
    }

    /** A place in the table queue. */
    public static final class Ticket {
        private final CompletableFuture<Seated> result = new CompletableFuture<>();
        private volatile WebSocket socket;
        private volatile ScheduledFuture<?> ping;

        public CompletableFuture<Seated> result() { return result; }

        public void cancel() {
            result.cancel(false);
            close();
        }

        private void close() {
            ScheduledFuture<?> p = ping;
            if (p != null) p.cancel(false);
            WebSocket s = socket;
            if (s != null) s.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        }
    }

    /** Waits in the table queue of {@code event}; {@code pod} hears the number of people waiting. */
    public static Ticket queue(String base, String event, String fingerprint, String version, String playerId, String name, String code,
                               Consumer<Integer> pod) {
        Ticket t = new Ticket();
        String b = trim(base);
        String url = b + "/tablequeue?q=" + enc(event) + "&fp=" + enc(fingerprint) + "&v=" + enc(version)
                + "&id=" + enc(playerId) + "&name=" + enc(name) + (code == null || code.isBlank() ? "" : "&code=" + enc(code));
        HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(url), new WebSocket.Listener() {
                    private final StringBuilder text = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket w) {
                        t.socket = w;
                        t.ping = KEEPALIVE.scheduleAtFixedRate(() -> w.sendText("ping", true), 20, 20, TimeUnit.SECONDS);
                        System.out.println("[mtga] table queue: waiting in " + event);
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
                                    String type = o.get("t").getAsString();
                                    if ("pod".equals(type)) pod.accept(o.get("n").getAsInt());
                                    else if ("table".equals(type)) {
                                        List<String> names = new ArrayList<>();
                                        o.getAsJsonArray("names").forEach(e -> names.add(e.getAsString()));
                                        t.result.complete(new Seated(b, o.get("table").getAsString(), o.get("slot").getAsInt(),
                                                o.get("token").getAsString(), o.get("n").getAsInt(), names));
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
                        t.result.completeExceptionally(new IOException("the coordinator closed the table queue (" + code + " " + reason + ")"));
                        t.close();
                        return null;
                    }

                    @Override
                    public void onError(WebSocket w, Throwable error) {
                        t.result.completeExceptionally(new IOException("coordinator: " + error.getMessage(), error));
                        t.close();
                    }
                }).exceptionally(e -> {
                    Throwable c = e.getCause() != null ? e.getCause() : e;
                    t.result.completeExceptionally(new IOException("coordinator unreachable: " + c.getMessage(), c));
                    return null;
                });
        return t;
    }

    /** What arrives on a room connection. Calls come one at a time from the link's thread. */
    public interface Listener {
        /** A table frame; {@code slot} = the sender's (on the host) or the target (on a guest). */
        void frame(int type, int slot, JsonObject body);

        /** A control message of the room: {"t":"in"|"out","slot"} on the host, {"t":"host"} / {"t":"left"} on a guest. */
        void control(JsonObject message);

        /** The connection is gone (closed by either side, or failed). */
        void closed();
    }

    private final WebSocket ws;
    private final ScheduledFuture<?> ping;
    private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
    private volatile boolean closed;

    private TableLink(WebSocket ws) {
        this.ws = ws;
        this.ping = KEEPALIVE.scheduleAtFixedRate(() -> {
            try { sendRaw(null, "ping"); } catch (IOException ignored) { }
        }, 20, 20, TimeUnit.SECONDS);
    }

    /** Opens the table's room as the seated slot. Nothing reaches {@code listener} before {@link #start()}. */
    public static TableLink open(Seated s, Listener listener) throws IOException {
        URI uri = URI.create(s.base() + "/table/" + s.table() + "?slot=" + s.slot() + "&token=" + s.token());
        CompletableFuture<TableLink> self = new CompletableFuture<>();
        WebSocket ws;
        try {
            ws = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10))
                    .buildAsync(uri, new WebSocket.Listener() {
                        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
                        private final StringBuilder text = new StringBuilder();
                        private boolean ended;

                        @Override
                        public void onOpen(WebSocket w) { /* nothing is read before start() */ }

                        private void end() {
                            if (ended) return;
                            ended = true;
                            self.thenAccept(l -> l.stopPing());
                            listener.closed();
                        }

                        @Override
                        public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
                            byte[] chunk = new byte[data.remaining()];
                            data.get(chunk);
                            partial.write(chunk, 0, chunk.length);
                            if (last) {
                                byte[] all = partial.toByteArray();
                                partial.reset();
                                if (all.length >= 2) {
                                    try {
                                        JsonObject body = all.length > 2 ? JsonParser.parseString(new String(all, 2, all.length - 2, StandardCharsets.UTF_8)).getAsJsonObject() : new JsonObject();
                                        listener.frame(all[0] & 0xff, all[1] & 0xff, body);
                                    } catch (RuntimeException e) {
                                        System.out.println("[mtga] table link: bad frame " + (all[0] & 0xff) + ": " + e);
                                    }
                                }
                            }
                            w.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                            text.append(data);
                            if (last) {
                                String msg = text.toString();
                                text.setLength(0);
                                if (!"pong".equals(msg)) {
                                    try {
                                        listener.control(JsonParser.parseString(msg).getAsJsonObject());
                                    } catch (RuntimeException e) {
                                        System.out.println("[mtga] table link: bad control message " + msg + ": " + e);
                                    }
                                }
                            }
                            w.request(1);
                            return null;
                        }

                        @Override
                        public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                            end();
                            return null;
                        }

                        @Override
                        public void onError(WebSocket w, Throwable error) {
                            System.out.println("[mtga] table link: " + error);
                            end();
                        }
                    }).get(12, TimeUnit.SECONDS);
        } catch (Exception e) {
            Throwable c = e.getCause() != null ? e.getCause() : e;
            throw new IOException("cannot open the draft table: " + c.getMessage(), c);
        }
        TableLink link = new TableLink(ws);
        self.complete(link);
        return link;
    }

    /** Sends a frame: to {@code slot} (host), or with any slot from a guest (the room puts the sender's). */
    public void send(int type, int slot, JsonObject body) throws IOException {
        byte[] json = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] msg = new byte[json.length + 2];
        msg[0] = (byte) type;
        msg[1] = (byte) slot;
        System.arraycopy(json, 0, msg, 2, json.length);
        sendRaw(msg, null);
    }

    /** As {@link #send}, logging instead of throwing (the other side may be gone; the room tells us). */
    public void trySend(int type, int slot, JsonObject body) {
        try {
            send(type, slot, body);
        } catch (IOException e) {
            System.out.println("[mtga] table link: send " + type + " to " + slot + " failed: " + e.getMessage());
        }
    }

    private synchronized void sendRaw(byte[] binary, String text) throws IOException {
        if (closed) throw new IOException("table link closed");
        try {
            lastSend.get(30, TimeUnit.SECONDS);                    // the WebSocket allows one outstanding send
            lastSend = binary != null ? ws.sendBinary(ByteBuffer.wrap(binary), true) : ws.sendText(text, true);
        } catch (Exception e) {
            throw new IOException("relay send failed: " + e.getMessage(), e);
        }
    }

    /** Starts delivering what arrives (call it once the link is stored where the listener looks for it). */
    public void start() { ws.request(1); }

    private void stopPing() { ping.cancel(false); }

    public boolean isClosed() { return closed; }

    public void close() {
        if (closed) return;
        try {
            sendRaw(new byte[] {(byte) BYE, 0, '{', '}'}, null);
        } catch (IOException ignored) { }
        closed = true;
        stopPing();
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
    }

    private static String trim(String base) { return base.endsWith("/") ? base.substring(0, base.length() - 1) : base; }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}

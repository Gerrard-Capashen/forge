package forge.mtga.social;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.mtga.profile.FileProfileStore;
import forge.mtga.profile.Profile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * This player's side of the coordinator's friends service: keeps the signed-in session WebSocket (presence and "changed"
 * pushes), the friend list / invites / blocks as the coordinator has them, and runs the actions the client's Friends UI asks for.
 * The client (the bridge's CoordinatorSocialManager) never talks to the coordinator itself: it reads {@link #snapshot()} and calls
 * the actions through {@link SocialEndpoints} on loopback. Every change of the state raises {@link #version()}.
 */
public final class SocialClient {
    private static final String[] STATUSES = {"Available", "Busy", "Away", "Offline"};

    private final Accounts accounts;
    private final FileProfileStore profiles;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "social-timer");
        t.setDaemon(true);
        return t;
    });

    private final Object lock = new Object();
    private long version = 1;
    private boolean connected;
    private String status = "Available";
    private JsonObject lists = emptyLists();
    private volatile String token;
    private volatile long tokenAt;
    private volatile WebSocket socket;
    private volatile boolean stopped;
    private final List<Consumer<JsonObject>> challengeListeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<JsonObject> messages = new ArrayDeque<>();      // friend messages for the client, newest last: {seq, from, payload, at}
    private long messageSeq;

    public SocialClient(Accounts accounts, FileProfileStore profiles) {
        this.accounts = accounts;
        this.profiles = profiles;
    }

    private static JsonObject emptyLists() {
        JsonObject o = new JsonObject();
        for (String k : new String[] {"friends", "incoming", "outgoing", "blocks"}) o.add(k, new JsonArray());
        return o;
    }

    /** Starts the session thread (signs in, registers if needed, reconnects with a growing pause). */
    public void start() {
        Thread t = new Thread(this::run, "social-session");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        stopped = true;
        WebSocket w = socket;
        if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        timer.shutdownNow();
    }

    // ---------------------------------------------------------------------------------------------------- state

    public long version() { synchronized (lock) { return version; } }

    private void bump() { synchronized (lock) { version++; lock.notifyAll(); } }

    /** The state the client's social manager mirrors: version, connection, self, friends (with presence), incoming / outgoing invites, blocks. */
    public JsonObject snapshot() {
        JsonObject o;
        synchronized (lock) {
            o = lists.deepCopy();
            o.addProperty("version", version);
            o.addProperty("connected", connected);
            o.addProperty("status", status);
            JsonArray msgs = new JsonArray();
            long now = System.currentTimeMillis();
            messages.removeIf(m -> now - m.get("at").getAsLong() > 120_000);
            for (JsonObject m : messages) msgs.add(m);
            o.add("messages", msgs);
        }
        Profile p = profiles.load();
        JsonObject self = new JsonObject();
        self.addProperty("playerId", p.playerId);
        self.addProperty("fullName", p.clientName());
        o.add("self", self);
        return o;
    }

    /** Waits until the version is above {@code since} (or the time is up) and answers the snapshot (long poll). */
    public JsonObject awaitChange(long since, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        synchronized (lock) {
            while (version <= since && System.currentTimeMillis() < end) lock.wait(Math.max(1, end - System.currentTimeMillis()));
        }
        return snapshot();
    }

    // ---------------------------------------------------------------------------------------------------- session

    private void run() {
        long pause = 2_000;
        while (!stopped) {
            CompletableFuture<Void> ended = new CompletableFuture<>();
            try {
                signIn();
                refresh();
                connect(ended);
                pause = 2_000;
                ended.get();                                              // until the socket closes
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                System.out.println("[mtga] social: " + (e.getMessage() != null ? e.getMessage() : e));
            }
            synchronized (lock) { if (connected) { connected = false; version++; lock.notifyAll(); } }
            if (stopped) return;
            try { Thread.sleep(pause); } catch (InterruptedException e) { return; }
            pause = Math.min(pause * 2, 60_000);
        }
    }

    private void signIn() throws IOException {
        Profile p = profiles.load();
        try {
            token = accounts.login(p.playerId);
        } catch (Accounts.ApiException e) {
            if (e.status != 404) throw e;
            accounts.register(p);                                          // not registered yet (the coordinator was down at start)
            token = accounts.login(p.playerId);
        }
        tokenAt = System.currentTimeMillis();
    }

    private String token() throws IOException {
        if (token == null || System.currentTimeMillis() - tokenAt > 20 * 3_600_000L) signIn();
        return token;
    }

    private void connect(CompletableFuture<Void> ended) throws Exception {
        String url = accounts.wsBase() + "/session?token=" + URLEncoder.encode(token(), StandardCharsets.UTF_8) + "&status=" + status;
        ScheduledFuture<?>[] ping = new ScheduledFuture<?>[1];
        WebSocket w = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), new WebSocket.Listener() {
            private final StringBuilder text = new StringBuilder();

            @Override
            public void onOpen(WebSocket ws) {
                ping[0] = timer.scheduleAtFixedRate(() -> ws.sendText("ping", true), 20, 20, TimeUnit.SECONDS);
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                text.append(data);
                if (last) {
                    String msg = text.toString();
                    text.setLength(0);
                    if (!"pong".equals(msg)) onMessage(msg);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                if (ping[0] != null) ping[0].cancel(false);
                ended.complete(null);
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                if (ping[0] != null) ping[0].cancel(false);
                ended.completeExceptionally(error);
            }
        }).get(15, TimeUnit.SECONDS);
        socket = w;
    }

    private void onMessage(String msg) {
        try {
            JsonObject m = JsonParser.parseString(msg).getAsJsonObject();
            switch (m.get("t").getAsString()) {
                case "hello" -> {
                    synchronized (lock) { connected = true; status = m.get("status").getAsString(); version++; lock.notifyAll(); }
                    System.out.println("[mtga] social: signed in (" + status + ")");
                }
                case "presence" -> setFriendStatus(m.get("playerId").getAsString(), m.get("status").getAsString());
                case "changed" -> refresh();
                case "challenge" -> {
                    for (Consumer<JsonObject> l : challengeListeners) {
                        try { l.accept(m); } catch (RuntimeException e) { System.out.println("[mtga] social: challenge event failed: " + e); }
                    }
                }
                case "game" -> {
                    JsonObject in = new JsonObject();
                    in.addProperty("from", m.get("from").getAsString());
                    in.addProperty("payload", m.get("payload").getAsString());
                    in.addProperty("at", System.currentTimeMillis());
                    synchronized (lock) {
                        in.addProperty("seq", ++messageSeq);
                        messages.addLast(in);
                        while (messages.size() > 30) messages.removeFirst();
                        version++;
                        lock.notifyAll();
                    }
                }
                default -> { }
            }
        } catch (RuntimeException | IOException e) {
            System.out.println("[mtga] social: bad message " + msg + " (" + e + ")");
        }
    }

    private void setFriendStatus(String playerId, String st) {
        synchronized (lock) {
            for (JsonElement f : lists.getAsJsonArray("friends")) {
                JsonObject o = f.getAsJsonObject();
                if (o.get("playerId").getAsString().equals(playerId)) o.addProperty("status", st);
            }
            version++;
            lock.notifyAll();
        }
    }

    /** Reads friends, invites and blocks again. */
    public void refresh() throws IOException {
        JsonObject o = call("GET", "/friends", null);
        synchronized (lock) {
            JsonObject l = emptyLists();
            for (String k : new String[] {"friends", "incoming", "outgoing", "blocks"}) if (o.has(k)) l.add(k, o.get(k));
            lists = l;
            version++;
            lock.notifyAll();
        }
    }

    // ---------------------------------------------------------------------------------------------------- actions

    private JsonObject call(String method, String path, JsonObject body) throws IOException {
        try {
            return accounts.call(method, path, body, token());
        } catch (Accounts.ApiException e) {
            if (e.status != 401) throw e;
            signIn();                                                       // the token expired
            return accounts.call(method, path, body, token());
        }
    }

    private static JsonObject who(String full, String playerId) {
        JsonObject b = new JsonObject();
        if (playerId != null && !playerId.isBlank()) b.addProperty("playerId", playerId);
        else b.addProperty("full", full == null ? "" : full);
        return b;
    }

    /** Invites by "Name#12345" or player id. Returns the coordinator's answer (the player; {@code accepted} when they had invited us). */
    public JsonObject invite(String full, String playerId) throws IOException { return changed(call("POST", "/friends/invite", who(full, playerId))); }
    public void accept(String playerId) throws IOException { changed(call("POST", "/friends/accept", who(null, playerId))); }
    public void decline(String playerId) throws IOException { changed(call("POST", "/friends/decline", who(null, playerId))); }
    public void revoke(String playerId) throws IOException { changed(call("POST", "/friends/revoke", who(null, playerId))); }
    public void remove(String playerId) throws IOException { changed(call("DELETE", "/friends/" + playerId, null)); }
    public JsonObject block(String full, String playerId) throws IOException { return changed(call("POST", "/blocks", who(full, playerId))); }
    public void unblock(String playerId) throws IOException { changed(call("DELETE", "/blocks/" + playerId, null)); }

    /** Whether the coordinator flags this player as a developer (orange name); false when unknown or the coordinator cannot be reached. */
    public boolean isDev(String playerId) {
        if (playerId == null || playerId.length() != 36) return false;          // not a registered player (the AI, an old peer)
        try {
            JsonObject o = call("GET", "/players/by-id?id=" + URLEncoder.encode(playerId, StandardCharsets.UTF_8), null);
            return o.has("dev") && o.get("dev").getAsBoolean();
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** "Name#12345" -> the player (for challenges). */
    public JsonObject resolve(String full) throws IOException { return call("GET", "/players/by-name?full=" + URLEncoder.encode(full, StandardCharsets.UTF_8), null); }

    private JsonObject changed(JsonObject answer) throws IOException {
        refresh();
        return answer;
    }

    /** The coordinator's challenge pushes ({"t":"challenge","kind":"status|countdown|match|kick|close",...}), on the session thread. */
    public void onChallengeEvent(Consumer<JsonObject> listener) { challengeListeners.add(listener); }

    /** A REST call of this player's (challenges, ...): token and re-login handled. */
    public JsonObject api(String method, String path, JsonObject body) throws IOException { return call(method, path, body); }

    /** An opaque message for a friend (the client's challenge invites); dropped by the coordinator when not friends or the friend is offline. */
    public boolean sendGame(String to, String payload) {
        WebSocket w = socket;
        if (w == null || to == null || to.isBlank() || payload == null) return false;
        JsonObject m = new JsonObject();
        m.addProperty("t", "game");
        m.addProperty("to", to);
        m.addProperty("payload", payload);
        w.sendText(m.toString(), true);
        return true;
    }

    /** The player's own presence: Available, Busy, Away or Offline (appears offline to friends, the session stays). */
    public void setStatus(String st) {
        for (String s : STATUSES) {
            if (s.equals(st)) {
                synchronized (lock) { status = st; version++; lock.notifyAll(); }
                WebSocket w = socket;
                if (w != null) {
                    JsonObject m = new JsonObject();
                    m.addProperty("t", "status");
                    m.addProperty("status", st);
                    w.sendText(m.toString(), true);
                }
                return;
            }
        }
    }
}

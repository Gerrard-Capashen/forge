package forge.mtga.peer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Framed connection between two Forge servers (host and guest of a player-vs-player match): 1 type byte, int32 length, body.
 * The guest's client talks to its own Match Door as usual; the guest's server relays that client's GRE messages to the
 * host's engine over this link and the host's answers back (see {@link RemoteSeatBackend}, {@link ProxySession}).
 * The frames travel over a TCP socket (friends on a private network) or through the coordinator's WebSocket room (online).
 */
public final class PeerLink {
    /** JSON: version, card DB fingerprint, key, player name, deck, printings. Guest -> host. */
    public static final int HELLO = 1;
    /** JSON: match id, host player name. Host -> guest. */
    public static final int HELLO_OK = 2;
    /** UTF-8 text: why the guest was not accepted. Host -> guest. */
    public static final int REJECT = 3;
    /** ClientToGREMessage (the client's ConnectReq). Guest -> host. */
    public static final int CONNECT = 4;
    /** ClientToGREMessage. Guest -> host. */
    public static final int MSG = 5;
    /** MatchServiceToClientMessage. Host -> guest. */
    public static final int TO_GUEST = 6;
    /** The sender is leaving. */
    public static final int BYE = 7;

    private static final int MAX_BODY = 16 * 1024 * 1024;

    public record Frame(int type, byte[] body) {
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    private interface Transport {
        void send(int type, byte[] body) throws IOException;
        /** Next frame, null when the other side closed. */
        Frame read() throws IOException;
        void setReadTimeout(int millis) throws IOException;
        void close();
    }

    private final Socket socket;
    private final Transport transport;

    public PeerLink(Socket socket) throws IOException {
        this.socket = socket;
        this.transport = new TcpTransport(socket);
    }

    private PeerLink(Transport transport) {
        this.socket = null;
        this.transport = transport;
    }

    /** A link through the coordinator's room (coordinator/src/index.ts): one WebSocket message = one frame. */
    public static PeerLink overWebSocket(URI uri, int connectMillis) throws IOException {
        return new PeerLink(new WsTransport(uri, connectMillis));
    }

    /** The TCP socket, null for a relayed link. */
    public Socket socket() { return socket; }

    public void send(int type, byte[] body) throws IOException { transport.send(type, body); }

    public void sendText(int type, String text) throws IOException { send(type, text.getBytes(StandardCharsets.UTF_8)); }

    /** Next frame, or null when the other side closed the link. */
    public Frame read() throws IOException { return transport.read(); }

    /** Longest wait for the next frame (0 = forever); a longer silence makes {@link #read} throw. */
    public void setReadTimeout(int millis) throws IOException { transport.setReadTimeout(millis); }

    public void close() { transport.close(); }

    private static final class TcpTransport implements Transport {
        private final Socket socket;
        private final DataInputStream in;
        private final DataOutputStream out;

        TcpTransport(Socket socket) throws IOException {
            this.socket = socket;
            socket.setTcpNoDelay(true);
            in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        }

        @Override
        public synchronized void send(int type, byte[] body) throws IOException {
            out.writeByte(type);
            out.writeInt(body.length);
            out.write(body);
            out.flush();
        }

        @Override
        public Frame read() throws IOException {
            int type;
            try {
                type = in.readUnsignedByte();
            } catch (java.io.EOFException e) {
                return null;
            }
            int len = in.readInt();
            if (len < 0 || len > MAX_BODY) throw new IOException("peer frame of " + len + " bytes");
            byte[] body = new byte[len];
            in.readFully(body);
            return new Frame(type, body);
        }

        @Override
        public void setReadTimeout(int millis) throws IOException { socket.setSoTimeout(millis); }

        @Override
        public void close() {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }

    /** Binary message = type byte + body. Text messages are the room's control messages ({"t":"peer"}, {"t":"left"}). */
    private static final class WsTransport implements Transport {
        private static final Frame END = new Frame(-1, new byte[0]);
        private final BlockingQueue<Frame> inbox = new LinkedBlockingQueue<>();
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream();
        private final WebSocket ws;
        private CompletableFuture<?> lastSend = CompletableFuture.completedFuture(null);
        private volatile int timeoutMillis;
        private volatile boolean closed;

        WsTransport(URI uri, int connectMillis) throws IOException {
            try {
                ws = HttpClient.newHttpClient().newWebSocketBuilder()
                        .connectTimeout(Duration.ofMillis(connectMillis))
                        .buildAsync(uri, new Listener()).get(connectMillis + 2000L, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                throw new IOException("coordinator unreachable: " + c.getMessage(), c);
            }
        }

        private final class Listener implements WebSocket.Listener {
            @Override
            public CompletionStage<?> onBinary(WebSocket w, ByteBuffer data, boolean last) {
                byte[] chunk = new byte[data.remaining()];
                data.get(chunk);
                partial.write(chunk, 0, chunk.length);
                if (last) {
                    byte[] all = partial.toByteArray();
                    partial.reset();
                    if (all.length > 0) inbox.add(new Frame(all[0] & 0xff, Arrays.copyOfRange(all, 1, all.length)));
                }
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                if (data.toString().contains("\"left\"")) inbox.add(END);      // the other side is gone
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                inbox.add(END);
                return null;
            }

            @Override
            public void onError(WebSocket w, Throwable error) { inbox.add(END); }
        }

        @Override
        public synchronized void send(int type, byte[] body) throws IOException {
            if (closed) throw new IOException("link closed");
            byte[] msg = new byte[body.length + 1];
            msg[0] = (byte) type;
            System.arraycopy(body, 0, msg, 1, body.length);
            try {
                lastSend.get(30, TimeUnit.SECONDS);                    // the WebSocket allows one outstanding send
                lastSend = ws.sendBinary(ByteBuffer.wrap(msg), true);
            } catch (Exception e) {
                throw new IOException("relay send failed: " + e.getMessage(), e);
            }
        }

        @Override
        public Frame read() throws IOException {
            try {
                Frame f = timeoutMillis > 0 ? inbox.poll(timeoutMillis, TimeUnit.MILLISECONDS) : inbox.take();
                if (f == null) throw new SocketTimeoutException("no frame from the peer in " + timeoutMillis + " ms");
                if (f == END) {
                    inbox.add(END);                                    // stays closed for later reads
                    return null;
                }
                return f;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            }
        }

        @Override
        public void setReadTimeout(int millis) { timeoutMillis = millis; }

        @Override
        public void close() {
            closed = true;
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
            inbox.add(END);
        }
    }
}

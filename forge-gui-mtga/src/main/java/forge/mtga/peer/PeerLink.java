package forge.mtga.peer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Framed connection between two Forge servers (host and guest of a player-vs-player match): 1 type byte, int32 length, body.
 * The guest's client talks to its own Match Door as usual; the guest's server relays that client's GRE messages to the
 * host's engine over this link and the host's answers back (see {@link RemoteSeatBackend}, {@link ProxySession}).
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
        public String text() { return new String(body, java.nio.charset.StandardCharsets.UTF_8); }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;

    public PeerLink(Socket socket) throws IOException {
        this.socket = socket;
        socket.setTcpNoDelay(true);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
    }

    public Socket socket() { return socket; }

    public synchronized void send(int type, byte[] body) throws IOException {
        out.writeByte(type);
        out.writeInt(body.length);
        out.write(body);
        out.flush();
    }

    public void sendText(int type, String text) throws IOException { send(type, text.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }

    /** Next frame, or null when the other side closed the link. */
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

    public void close() {
        try { socket.close(); } catch (IOException ignored) { }
    }
}

package forge.mtga.peer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wizards.mtga.gre.ClientToGREMessage;
import forge.StaticData;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.ForgeBackend;
import forge.mtga.forge.ForgeMatch;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Host side of player-vs-player: listens for one guest while the host's client waits in its queue screen. The guest
 * names its deck; the match then runs in the host's engine with the guest's client reached through a {@link ProxySession}.
 */
public final class PeerHost {
    /** A host waiting for a guest: the shared match with the host's seat already in it. */
    public record Offer(String matchId, ForgeMatch room, Consumer<String> onGuestJoined) { }

    private final CardDb db;
    private final int port;
    private final String key;
    private final String fingerprint;
    private volatile Offer offer;

    public PeerHost(CardDb db, int port, String key, String fingerprint) {
        this.db = db;
        this.port = port;
        this.key = key == null ? "" : key;
        this.fingerprint = fingerprint;
    }

    public void start() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(port));
        System.out.println("[mtga] Peer host listening on 0.0.0.0:" + port + " (card DB " + fingerprint + ")");
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Socket s = server.accept();
                    Thread h = new Thread(() -> serve(s), "peer-host-guest");
                    h.setDaemon(true);
                    h.start();
                } catch (IOException e) {
                    System.out.println("[mtga] peer host stopped: " + e);
                    return;
                }
            }
        }, "peer-host-accept");
        t.setDaemon(true);
        t.start();
    }

    public void offer(Offer o) {
        offer = o;
        System.out.println("[mtga] peer: waiting for a guest (match " + o.matchId() + ")");
    }

    /** The host's client left the queue. */
    public void cancel() {
        if (offer != null) System.out.println("[mtga] peer: no longer waiting for a guest");
        offer = null;
    }

    private void serve(Socket socket) {
        PeerLink link = null;
        ForgeBackend theirs = null;
        ProxySession proxy = null;
        try {
            socket.setSoTimeout(10_000);
            link = new PeerLink(socket);
            PeerLink.Frame hello = link.read();
            if (hello == null || hello.type() != PeerLink.HELLO) return;
            JsonObject h = JsonParser.parseString(hello.text()).getAsJsonObject();
            String reject = check(h);
            Offer o = null;
            ForgeMatch.Seat seat = null;
            if (reject == null) {
                seat = seatOf(h);
                if (seat == null) reject = "the guest's deck has no cards Forge knows";
            }
            if (reject == null) {
                synchronized (this) {
                    o = offer;
                    if (o == null) reject = "the host is not waiting for a game (start a game on the host first)";
                    else offer = null;
                }
            }
            if (reject != null) {
                System.out.println("[mtga] peer: rejected " + socket.getRemoteSocketAddress() + ": " + reject);
                link.sendText(PeerLink.REJECT, reject);
                return;
            }
            socket.setSoTimeout(0);
            theirs = new ForgeBackend(db, o.room(), 1, seat);
            proxy = new ProxySession(link);
            JsonObject ok = new JsonObject();
            ok.addProperty("matchId", o.matchId());
            ok.addProperty("name", System.getProperty("mtga.name", "Forge Player"));
            link.sendText(PeerLink.HELLO_OK, ok.toString());
            System.out.println("[mtga] peer: guest '" + seat.name() + "' joined match " + o.matchId() + " from " + socket.getRemoteSocketAddress());
            o.onGuestJoined().accept(seat.name());

            PeerLink.Frame f;
            while ((f = link.read()) != null) {
                switch (f.type()) {
                    case PeerLink.CONNECT -> theirs.onConnect(proxy, ClientToGREMessage.parseFrom(f.body()));
                    case PeerLink.MSG -> theirs.onClientMessage(proxy, ClientToGREMessage.parseFrom(f.body()));
                    case PeerLink.BYE -> { return; }
                    default -> { }
                }
            }
        } catch (Exception e) {
            System.out.println("[mtga] peer: guest link failed: " + e);
        } finally {
            if (theirs != null) theirs.onDisconnect(proxy);
            if (link != null) link.close();
        }
    }

    private String check(JsonObject h) {
        if (!h.has("v") || h.get("v").getAsInt() != PeerGuest.VERSION) return "different peer protocol version";
        if (!fingerprint.equals(str(h, "fp"))) {
            return "different card database (host " + fingerprint + ", guest " + str(h, "fp") + "): both players need the same MTGA build and MTGA-Forge version";
        }
        if (!key.equals(str(h, "key"))) return "wrong key";
        return null;
    }

    private ForgeMatch.Seat seatOf(JsonObject h) {
        Map<String, Integer> deck = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : h.getAsJsonObject("deck").entrySet()) {
            if (StaticData.instance().getCommonCards().getUniqueByName(e.getKey()) != null) deck.put(e.getKey(), e.getValue().getAsInt());
        }
        if (deck.isEmpty()) return null;
        Map<String, Integer> printing = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : h.getAsJsonObject("printing").entrySet()) {
            int grp = e.getValue().getAsInt();
            if (db.name(grp) != null) printing.put(e.getKey(), grp);       // a printing this client build does not have is ignored
        }
        String name = str(h, "name");
        return new ForgeMatch.Seat(name.isBlank() ? "Guest" : name, deck, printing);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}

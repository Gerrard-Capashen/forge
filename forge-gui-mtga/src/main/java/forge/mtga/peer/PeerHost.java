package forge.mtga.peer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wizards.mtga.gre.ClientToGREMessage;
import forge.StaticData;
import forge.mtga.forge.CardDb;
import forge.mtga.forge.ForgeBackend;
import forge.mtga.forge.ForgeMatch;
import forge.mtga.profile.PublicProfile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Host side of player-vs-player: listens for one guest while the host's client waits in its queue screen. The guest
 * names its deck; the match then runs in the host's engine with the guest's client reached through a {@link ProxySession}.
 */
public final class PeerHost {
    /** A host waiting for a guest: the shared match with the host's seat already in it, the host's public profile for this match, and who to tell when the guest's profile arrives. */
    /** {@code queue} = the queue event the host waits in ("CasualPlay_Modern"); a guest must ask for the same one ("" = no queue). */
    public record Offer(String matchId, ForgeMatch room, PublicProfile host, String queue, Consumer<PublicProfile> onGuestJoined) {
        public Offer(String matchId, ForgeMatch room, PublicProfile host, Consumer<PublicProfile> onGuestJoined) { this(matchId, room, host, "", onGuestJoined); }
    }

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
        PeerLink link;
        try {
            link = new PeerLink(socket);
        } catch (IOException e) {
            System.out.println("[mtga] peer: guest link failed: " + e);
            return;
        }
        serve(link, String.valueOf(socket.getRemoteSocketAddress()));
    }

    /** Online: the guest the coordinator paired with this host arrives through the room instead of the listening socket. Blocks for the match. */
    public void serveOnline(PeerLink link) { serve(link, "the coordinator room"); }

    private void serve(PeerLink link, String remote) {
        ForgeBackend theirs = null;
        ProxySession proxy = null;
        try {
            link.setReadTimeout(10_000);
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
                    else if (!o.queue().equals(str(h, "queue"))) {
                        reject = "the host waits in " + queueName(o.queue()) + ", you chose " + queueName(str(h, "queue")) + ": both players need the same queue";
                        o = null;
                    } else offer = null;
                }
            }
            if (reject != null) {
                System.out.println("[mtga] peer: rejected " + remote + ": " + reject);
                link.sendText(PeerLink.REJECT, reject);
                return;
            }
            link.setReadTimeout(0);
            theirs = new ForgeBackend(db, o.room(), 1, seat);
            final PeerLink guestLink = link;
            theirs.setMetricsListener(m -> {            // the guest's quest counters: its own server reports them
                try { guestLink.sendText(PeerLink.METRICS, m.toString()); } catch (IOException ignored) { }
            });
            theirs.setOutcomeListener(outcome -> {      // the guest's own server reports it to the coordinator
                try { guestLink.sendText(PeerLink.RESULT, outcome); } catch (IOException ignored) { }
            });
            proxy = new ProxySession(link);
            JsonObject ok = new JsonObject();
            ok.addProperty("matchId", o.matchId());
            ok.addProperty("name", o.host().name());
            ok.add("profile", o.host().toJson());
            if (!o.room().commanders(0).isEmpty()) ok.add("commanders", new com.google.gson.Gson().toJsonTree(o.room().commanders(0)));       // the host's (Brawl): the guest's versus screen shows them
            link.sendText(PeerLink.HELLO_OK, ok.toString());
            System.out.println("[mtga] peer: guest '" + seat.name() + "' joined match " + o.matchId() + " from " + remote);
            o.onGuestJoined().accept(guestProfile(h, seat.name()));

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
            link.close();
        }
    }

    private static String queueName(String q) { return q == null || q.isEmpty() ? "no queue" : q; }

    private String check(JsonObject h) {
        if (!h.has("v") || h.get("v").getAsInt() != PeerGuest.VERSION) return "different peer protocol version";
        if (!fingerprint.equals(str(h, "fp"))) {
            return "different card database (host " + fingerprint + ", guest " + str(h, "fp") + "): both players need the same MTGA build and MTGA-Forge version";
        }
        if (!key.equals(str(h, "key"))) return "wrong key";
        return null;
    }

    /** The guest's public profile from its HELLO (a peer without one is shown by name only). */
    static PublicProfile guestProfile(JsonObject h, String name) {
        PublicProfile p = h.has("profile") && h.get("profile").isJsonObject() ? PublicProfile.fromJson(h.getAsJsonObject("profile")) : null;
        return p != null ? p : new PublicProfile("", name, "", "", "", "", null, "", 4);
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
        Map<String, Integer> side = new LinkedHashMap<>();       // a guest of an older build sends none
        if (h.has("sideboard") && h.get("sideboard").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : h.getAsJsonObject("sideboard").entrySet()) {
                if (StaticData.instance().getCommonCards().getUniqueByName(e.getKey()) != null && db.grpId(e.getKey()) != 0) side.put(e.getKey(), e.getValue().getAsInt());
            }
        }
        String name = str(h, "name");
        String companion = str(h, "companion");       // a guest of an older build sends none
        if (companion.isBlank() || StaticData.instance().getCommonCards().getUniqueByName(companion) == null || db.grpId(companion) == 0) companion = null;
        List<String> commanders = new java.util.ArrayList<>();       // a guest of an older build sends none
        if (h.has("commanders") && h.get("commanders").isJsonArray()) {
            for (JsonElement e : h.getAsJsonArray("commanders")) {
                String c = e.getAsString();
                if (StaticData.instance().getCommonCards().getUniqueByName(c) != null && db.grpId(c) != 0) commanders.add(c);
            }
        }
        return new ForgeMatch.Seat(name.isBlank() ? "Guest" : name, deck, printing, side, companion, commanders);
    }

    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
}

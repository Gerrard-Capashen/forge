package forge.mtga.peer;

import com.wizards.mtga.gre.ClientToGREMessage;
import com.wizards.mtga.gre.MatchServiceToClientMessage;
import forge.mtga.net.GameBackend;
import forge.mtga.net.MatchSession;

import java.io.IOException;

/**
 * The guest's side of a player-vs-player match. The guest's client connects to its own Match Door like in any match; this
 * backend relays what the client sends to the host's engine and what the host sends back to the client.
 */
public final class RemoteSeatBackend implements GameBackend {
    private final PeerLink link;
    private volatile MatchSession session;
    private volatile boolean leaving;
    private volatile boolean resultSeen;
    private volatile java.util.function.Consumer<String> outcomeListener;

    private volatile java.util.function.Consumer<com.google.gson.JsonObject> metricsListener;

    /** This seat's quest counters, as the host's engine counted them (frame METRICS; never called with an older host). */
    public void setMetricsListener(java.util.function.Consumer<com.google.gson.JsonObject> l) { metricsListener = l; }

    /** Told once how the match ended for this seat ("win" | "loss" | "draw"): the host says so, or "win" when the host vanished without a word. */
    public void setOutcomeListener(java.util.function.Consumer<String> l) { outcomeListener = l; }

    private void tellOutcome(String outcome) {
        if (resultSeen) return;
        resultSeen = true;
        java.util.function.Consumer<String> l = outcomeListener;
        if (l != null) l.accept(outcome);
    }

    RemoteSeatBackend(PeerLink link) {
        this.link = link;
        Thread reader = new Thread(this::readLoop, "peer-guest-reader");
        reader.setDaemon(true);
        reader.start();
    }

    @Override
    public void onConnect(MatchSession s, ClientToGREMessage connectReq) {
        session = s;
        relay(PeerLink.CONNECT, connectReq);
    }

    @Override
    public void onClientMessage(MatchSession s, ClientToGREMessage msg) { relay(PeerLink.MSG, msg); }

    @Override
    public void onDisconnect(MatchSession s) {
        leaving = true;
        try {
            link.send(PeerLink.BYE, new byte[0]);
        } catch (IOException ignored) { }
        link.close();
    }

    private void relay(int type, ClientToGREMessage msg) {
        try {
            link.send(type, msg.toByteArray());
        } catch (IOException e) {
            System.out.println("[mtga] peer: host link lost: " + e);
            hostGone();
        }
    }

    private void readLoop() {
        try {
            PeerLink.Frame f;
            while ((f = link.read()) != null) {
                if (f.type() == PeerLink.TO_GUEST) {
                    MatchServiceToClientMessage.Builder b = MatchServiceToClientMessage.parseFrom(f.body()).toBuilder();
                    MatchSession s = session;
                    if (s != null) s.send(b, b.getRequestId());
                } else if (f.type() == PeerLink.METRICS) {
                    java.util.function.Consumer<com.google.gson.JsonObject> ml = metricsListener;
                    try {
                        if (ml != null) ml.accept(com.google.gson.JsonParser.parseString(f.text()).getAsJsonObject());
                    } catch (RuntimeException e) {
                        System.out.println("[mtga] peer: bad quest counters from the host: " + e.getMessage());
                    }
                } else if (f.type() == PeerLink.RESULT) {
                    tellOutcome(f.text());
                } else if (f.type() == PeerLink.BYE) {
                    break;
                }
            }
        } catch (IOException e) {
            if (!leaving) System.out.println("[mtga] peer: host link lost: " + e);
        }
        hostGone();
    }

    private void hostGone() {
        if (!leaving) tellOutcome("win");     // the host dropped before the game ended: the coordinator checks that with what its relay room saw
        MatchSession s = session;
        link.close();
        if (s != null) s.close();      // the client shows its own "connection lost"
    }
}

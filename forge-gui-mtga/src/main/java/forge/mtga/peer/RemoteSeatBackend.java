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
        MatchSession s = session;
        link.close();
        if (s != null) s.close();      // the client shows its own "connection lost"
    }
}

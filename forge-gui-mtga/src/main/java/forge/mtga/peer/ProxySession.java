package forge.mtga.peer;

import com.wizards.mtga.gre.MatchServiceToClientMessage;
import forge.mtga.net.MatchSession;

import java.io.IOException;

/** Host side of the guest's client: everything the engine sends to it goes over the peer link to the guest's server. */
final class ProxySession extends MatchSession {
    private final PeerLink link;

    ProxySession(PeerLink link) { this.link = link; }

    @Override
    public synchronized void send(MatchServiceToClientMessage.Builder m, int requestId) {
        m.setRequestId(requestId);
        m.setTimestamp(System.currentTimeMillis());
        try {
            link.send(PeerLink.TO_GUEST, m.build().toByteArray());
        } catch (IOException e) {
            link.close();      // the reader thread notices and reports the disconnect to the engine
        }
    }

    @Override
    public void close() { link.close(); }
}

package forge.mtga.net;

import com.wizards.mtga.gre.ClientToGREMessage;

/** Game side of a Match Door connection. One instance per match; the transport owns the socket. */
public interface GameBackend {
    /** Client connected and sent its ConnectReq: reply with ConnectResp + initial state via the session. */
    void onConnect(MatchSession session, ClientToGREMessage connectReq);

    /** Any later ClientToGREMessage (mulligan, action, select...). */
    void onClientMessage(MatchSession session, ClientToGREMessage msg);

    void onDisconnect(MatchSession session);
}

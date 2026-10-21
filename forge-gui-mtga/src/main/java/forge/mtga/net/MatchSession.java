package forge.mtga.net;

import com.wizards.mtga.gre.*;
import io.netty.channel.ChannelHandlerContext;

/** Server-side view of one connected client: sends GRE batches and room events. */
public class MatchSession {
    private final ChannelHandlerContext ctx;
    private int msgId;
    public String clientId = "";

    MatchSession(ChannelHandlerContext ctx) { this.ctx = ctx; }

    /** For in-process clients (tests): override {@link #send}. */
    protected MatchSession() { this.ctx = null; }

    public synchronized void send(MatchServiceToClientMessage.Builder m, int requestId) {
        if (ctx == null) return;
        m.setRequestId(requestId);
        m.setTimestamp(System.currentTimeMillis());
        byte[] body = m.build().toByteArray();
        ctx.writeAndFlush(FrameCodec.encode(ctx, FrameCodec.TYPE_MSG, FrameCodec.FMT_PROTOBUF, body));
    }

    /** Send several GRE messages in one GreToClientEvent; msgIds are assigned here. */
    public synchronized void sendGre(GREToClientMessage.Builder... msgs) {
        MatchServiceToClientMessage.Builder out = MatchServiceToClientMessage.newBuilder();
        for (GREToClientMessage.Builder g : msgs) {
            g.setMsgId(++msgId);
            Capture.log("SERVER -> " + summary(g));
            out.getGreToClientEventBuilder().addGreToClientMessages(g);
        }
        send(out, 0);
    }

    private static String summary(GREToClientMessage.Builder g) {
        StringBuilder sb = new StringBuilder(g.getType().name().replace("GREMessageType_", ""));
        sb.append(" msgId=").append(g.getMsgId()).append(" gsId=").append(g.getGameStateId());
        if (g.hasGameStateMessage()) {
            GameStateMessage gs = g.getGameStateMessage();
            sb.append(" [").append(gs.getType().name().replace("GameStateType_", "")).append(" turn=").append(gs.getTurnInfo().getTurnNumber())
                    .append(' ').append(gs.getTurnInfo().getPhase().name().replace("Phase_", ""))
                    .append('/').append(gs.getTurnInfo().getStep().name().replace("Step_", ""))
                    .append(" objs=").append(gs.getGameObjectsCount()).append(" zones=").append(gs.getZonesCount())
                    .append(" annotations=");
            for (AnnotationInfo a : gs.getAnnotationsList()) {
                for (AnnotationType t : a.getTypeList()) sb.append(t.name().replace("AnnotationType_", ""));
                if (a.getType(0) == AnnotationType.AnnotationType_ZoneTransfer) {
                    sb.append('(').append(a.getAffectedIds(0));
                    for (KeyValuePairInfo d : a.getDetailsList()) {
                        sb.append(' ').append(d.getValueInt32Count() > 0 ? String.valueOf(d.getValueInt32(0)) : d.getValueStringCount() > 0 ? d.getValueString(0) : "");
                    }
                    sb.append(')');
                }
                sb.append(',');
            }
            if (gs.hasTurnInfo()) sb.append(" prio=").append(gs.getTurnInfo().getPriorityPlayer()).append(" decide=").append(gs.getTurnInfo().getDecisionPlayer());
            sb.append(']');
        } else {
            sb.append(' ').append(Capture.flat(g.build(), 700));
        }
        return sb.toString();
    }

    public void sendRoomState(MatchGameRoomStateType state, String matchId) {
        MatchServiceToClientMessage.Builder out = MatchServiceToClientMessage.newBuilder();
        MatchGameRoomInfo.Builder info = out.getMatchGameRoomStateChangedEventBuilder().getGameRoomInfoBuilder();
        info.setStateType(state);
        info.getGameRoomConfigBuilder().setMatchId(matchId);
        send(out, 0);
    }

    public void close() { ctx.close(); }
}

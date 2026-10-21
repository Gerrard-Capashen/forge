package forge.mtga.fd;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.wizards.mtga.fd.Response;
import com.wizards.mtga.fd.ServerError;
import forge.mtga.net.FrameCodec;
import io.netty.channel.ChannelHandlerContext;

import java.nio.charset.StandardCharsets;

/** One connected client of the Front Door: replies (matched by TransId) and push notifications. */
public final class FdSession {
    private final ChannelHandlerContext ctx;

    FdSession(ChannelHandlerContext ctx) { this.ctx = ctx; }

    public boolean isOpen() { return ctx.channel().isActive(); }

    void replyJson(String transId, String json) {
        send(Response.newBuilder().setRawTransId(transId).setJsonPayload(ByteString.copyFrom(json, StandardCharsets.UTF_8)).build());
    }

    void replyProto(String transId, Message payload) {
        send(Response.newBuilder().setRawTransId(transId).setProtobufPayload(Any.pack(payload)).build());
    }

    void replyError(String transId, int code, String message) {
        send(Response.newBuilder().setRawTransId(transId)
                .setError(ServerError.newBuilder().setCode(code).setMessage(message)).build());
    }

    /** Unsolicited message: a JSON PushNotification (MatchCreated, InventoryChanged...). No TransId. */
    public void push(String json) {
        send(Response.newBuilder().setJsonPayload(ByteString.copyFrom(json, StandardCharsets.UTF_8)).build());
    }

    private void send(Response r) {
        byte[] body = r.toByteArray();
        ctx.writeAndFlush(FrameCodec.encode(ctx, FrameCodec.TYPE_MSG, FrameCodec.FMT_PROTOBUF, body));
    }
}

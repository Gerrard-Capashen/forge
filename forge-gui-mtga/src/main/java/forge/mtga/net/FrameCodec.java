package forge.mtga.net;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/** MTGA transport frame: [version=4][type | format shifted left 4][int32 LE bodyLen][body]. */
public final class FrameCodec extends ByteToMessageDecoder {
    public static final int TYPE_MSG = 1, TYPE_PING = 2, TYPE_PONG = 3;
    public static final int FMT_JSON = 1, FMT_PROTOBUF = 2;

    public record Frame(int type, int format, byte[] body) { }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        in.markReaderIndex();
        if (in.readableBytes() < 2) return;
        int ver = in.readUnsignedByte();
        int type, format;
        if (ver >= 4) {
            int tb = in.readUnsignedByte();
            type = tb & 0xF;
            format = (tb >> 4) & 0xF;
        } else if (ver == 3) {
            type = in.readUnsignedByte() & 0xF;
            format = FMT_PROTOBUF;
        } else {
            type = TYPE_MSG;
            format = FMT_PROTOBUF;
            in.readerIndex(in.readerIndex() - 1);
        }
        if (in.readableBytes() < 4) { in.resetReaderIndex(); return; }
        int len = in.readIntLE();
        if (len < 0 || len > (64 << 20)) throw new IllegalStateException("bad frame length " + len);
        if (in.readableBytes() < len) { in.resetReaderIndex(); return; }
        byte[] body = new byte[len];
        in.readBytes(body);
        out.add(new Frame(type, format, body));
    }

    public static ByteBuf encode(ChannelHandlerContext ctx, int type, int format, byte[] body) {
        ByteBuf b = ctx.alloc().buffer(6 + body.length);
        b.writeByte(4).writeByte((type & 0xF) | (format << 4)).writeIntLE(body.length).writeBytes(body);
        return b;
    }
}

package forge.mtga.fd;

import com.wizards.mtga.fd.Cmd;
import forge.mtga.net.Capture;
import forge.mtga.net.FrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Local MTGA Front Door (lobby/collection/decks/events) on loopback. Same transport as the Match Door: TLS + MTGA
 * frames; every message is a protobuf {@code Wizards.Arena.Protocol.Cmd} (client) / {@code Response} (server) whose
 * payload is either JSON bytes or a protobuf Any, keyed by CmdType. See docs/protocol.md "Front Door".
 */
public final class FrontDoorServer {
    private final int port;
    private final FrontDoor frontDoor;
    private EventLoopGroup boss, workers;

    public FrontDoorServer(int port, FrontDoor frontDoor) {
        this.port = port;
        this.frontDoor = frontDoor;
    }

    public void start() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        SslContext ssl = SslContextBuilder.forServer(cert.certificate(), cert.privateKey()).protocols("TLSv1.2").build();
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(2);
        new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(ssl.newHandler(ch.alloc()), new FrameCodec(), new Handler());
                    }
                }).bind("127.0.0.1", port).sync();
        System.out.println("[mtga] Front Door listening on 127.0.0.1:" + port);
    }

    public void stop() {
        if (boss != null) boss.shutdownGracefully();
        if (workers != null) workers.shutdownGracefully();
    }

    private final class Handler extends SimpleChannelInboundHandler<FrameCodec.Frame> {
        private FdSession session;

        @Override public void channelActive(ChannelHandlerContext ctx) {
            session = new FdSession(ctx);
            frontDoor.onConnect(session);
            Capture.log("FD connected");
        }

        @Override public void channelInactive(ChannelHandlerContext ctx) {
            frontDoor.onDisconnect(session);
            Capture.log("FD disconnected");
        }

        @Override protected void channelRead0(ChannelHandlerContext ctx, FrameCodec.Frame f) throws Exception {
            if (f.type() == FrameCodec.TYPE_PING) {
                ctx.writeAndFlush(FrameCodec.encode(ctx, FrameCodec.TYPE_PONG, f.format(), f.body()));
                return;
            }
            if (f.type() != FrameCodec.TYPE_MSG) return;
            frontDoor.handle(session, Cmd.parseFrom(f.body()));
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            System.out.println("[mtga] FD connection error: " + cause);
            cause.printStackTrace(System.out);
            ctx.close();
        }
    }
}

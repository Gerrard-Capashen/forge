package forge.mtga.net;

import com.wizards.mtga.gre.*;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

import java.util.function.Supplier;

/** TLS Match Door on loopback. The patched client only trusts a self-signed CN=localhost certificate. */
public final class MatchDoorServer {
    private final int port;
    private final Supplier<GameBackend> backendFactory;
    private EventLoopGroup boss, workers;

    public MatchDoorServer(int port, Supplier<GameBackend> backendFactory) {
        this.port = port;
        this.backendFactory = backendFactory;
    }

    public void start() throws Exception {
        SelfSignedCertificate cert = new SelfSignedCertificate("localhost");
        SslContext ssl = SslContextBuilder.forServer(cert.certificate(), cert.privateKey())
                .protocols("TLSv1.2").build();
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        new ServerBootstrap().group(boss, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(ssl.newHandler(ch.alloc()), new FrameCodec(), new Handler(backendFactory.get()));
                    }
                }).bind("127.0.0.1", port).sync();
        System.out.println("[mtga] Match Door listening on 127.0.0.1:" + port);
    }

    public void stop() {
        if (boss != null) boss.shutdownGracefully();
        if (workers != null) workers.shutdownGracefully();
    }

    private static final class Handler extends SimpleChannelInboundHandler<FrameCodec.Frame> {
        private final GameBackend backend;
        private MatchSession session;

        Handler(GameBackend backend) { this.backend = backend; }

        @Override public void channelActive(ChannelHandlerContext ctx) { session = new MatchSession(ctx); }

        @Override public void channelInactive(ChannelHandlerContext ctx) { backend.onDisconnect(session); }

        @Override protected void channelRead0(ChannelHandlerContext ctx, FrameCodec.Frame f) throws Exception {
            if (f.type() == FrameCodec.TYPE_PING) {
                ctx.writeAndFlush(FrameCodec.encode(ctx, FrameCodec.TYPE_PONG, f.format(), f.body()));
                return;
            }
            if (f.type() != FrameCodec.TYPE_MSG) return;
            ClientToMatchServiceMessage m = ClientToMatchServiceMessage.parseFrom(f.body());
            Capture.log("CLIENT envelope " + m.getClientToMatchServiceMessageType());
            switch (m.getClientToMatchServiceMessageType()) {
                case ClientToMatchServiceMessageType_AuthenticateRequest -> {
                    AuthenticateRequest r = AuthenticateRequest.parseFrom(m.getPayload());
                    session.clientId = r.getClientId();
                    MatchServiceToClientMessage.Builder out = MatchServiceToClientMessage.newBuilder();
                    out.getAuthenticateResponseBuilder().setClientId(r.getClientId()).setSessionId("local-session").setScreenName(System.getProperty("mtga.name", "Forge Player"));
                    out.setTransactionId(m.getTransactionId());
                    session.send(out, m.getRequestId());
                }
                case ClientToMatchServiceMessageType_ClientToMatchDoorConnectRequest -> {
                    ClientToMatchDoorConnectRequest r = ClientToMatchDoorConnectRequest.parseFrom(m.getPayload());
                    backend.onConnect(session, ClientToGREMessage.parseFrom(r.getClientToGreMessageBytes()));
                }
                case ClientToMatchServiceMessageType_ClientToGREMessage, ClientToMatchServiceMessageType_ClientToGREUIMessage ->
                    backend.onClientMessage(session, ClientToGREMessage.parseFrom(m.getPayload()));
                default -> {
                    System.out.println("[mtga] unhandled " + m.getClientToMatchServiceMessageType());
                    Capture.log("UNHANDLED envelope " + m.getClientToMatchServiceMessageType());
                }
            }
        }

        @Override public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            cause.printStackTrace();
            ctx.close();
        }
    }
}

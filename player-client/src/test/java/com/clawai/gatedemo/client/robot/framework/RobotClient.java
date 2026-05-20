package com.clawai.gatedemo.client.robot.framework;

import com.clawai.gatedemo.client.protocol.codec.ClientMessageDecoder;
import com.clawai.gatedemo.client.protocol.codec.ClientMessageEncoder;
import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.client.protocol.model.RawMessageBody;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * 自动化测试用的轻量级 robot client：复用 player-client 的 codec 与协议模型，
 * 但去除了 Spring 依赖与命令行 console，专为脚本化场景设计。
 *
 * <p>典型用法：
 * <pre>{@code
 * try (RobotClient r = RobotClient.builder()
 *         .host("127.0.0.1").wsPort(8888)
 *         .timeout(Duration.ofSeconds(5))
 *         .build()) {
 *     r.connect();
 *     AuthResponse resp = r.auth(token, gameId).get(5, SECONDS);
 *     // assert resp.getSuccess() ...
 *     r.send(MessageRouteRegistry.getIdByName("ClientHeartbeat"),
 *            ClientHeartbeat.newBuilder().setTimestamp(...).build().toByteArray());
 *     Optional<WrappedMessage> ack = r.awaitMessage(
 *         MessageRouteRegistry.getIdByName("HeartbeatAck"), Duration.ofSeconds(2));
 * }
 * }</pre>
 *
 * <p>非线程安全：单测/单场景内仅由测试主线程驱动。
 */
public final class RobotClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RobotClient.class);

    /** B1 client_features: bit0 supports_gw_seq | bit1 supports_resume_replay. */
    private static final int CLIENT_FEATURES = 0x3;

    static {
        try {
            MessageRouteRegistry.loadFromJson("message_registry.json");
        } catch (Throwable ignored) {
            // 已加载或测试 classpath 找不到时静默；调用方使用 MessageRouteRegistry 时若失败会自报错
        }
    }

    /**
     * 传输模式：WS（默认，复用 player-client 现有 ws codec）/ TCP（直连 gate.tcp.port，复用
     * gate-service 的 game codec 等价帧格式）。
     */
    public enum Transport { WS, TCP }

    private final String host;
    private final int port;
    private final Transport transport;
    private final String path;
    private final Duration connectTimeout;
    private final Duration receiveTimeout;
    private final boolean shareEventLoop;

    private EventLoopGroup group;
    private Channel channel;
    private RobotInboundHandler handler;
    private final AtomicLong lastClientRecvSeq = new AtomicLong(0L);
    private volatile String flowId = "";
    private volatile boolean handshakeDone = false;

    private RobotClient(Builder b) {
        this.host = b.host;
        this.port = b.port;
        this.transport = b.transport;
        this.path = b.path;
        this.connectTimeout = b.connectTimeout;
        this.receiveTimeout = b.receiveTimeout;
        this.shareEventLoop = b.shareEventLoop;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 同步连接 + 完成握手（WS 模式才有 handshake，TCP 模式跳过）；返回时可以立即 {@link #auth}。
     */
    public void connect() throws InterruptedException {
        this.group = new NioEventLoopGroup(1);
        Bootstrap b = new Bootstrap();
        this.handler = new RobotInboundHandler();
        final Transport mode = this.transport;
        b.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline p = ch.pipeline();
                        if (mode == Transport.WS) {
                            p.addLast("httpCodec", new HttpClientCodec());
                            p.addLast("httpAgg", new HttpObjectAggregator(8192));
                            WebSocketClientProtocolConfig wsCfg = WebSocketClientProtocolConfig.newBuilder()
                                    .webSocketUri("ws://" + host + ":" + port + path)
                                    .build();
                            p.addLast("wsProtocol", new WebSocketClientProtocolHandler(wsCfg));
                            p.addLast("wsBinaryEncoder", new com.clawai.gatedemo.client.ws.codec.WebSocketBinaryEncoder());
                            p.addLast("wsBinaryDecoder", new com.clawai.gatedemo.client.ws.codec.WebSocketBinaryDecoder());
                        } else {
                            // TCP 模式：直接使用 16 字节定长头 + 变长 body 的二进制协议，
                            // 复用 player-client 现有 ClientMessage{Encoder,Decoder}（与 gate
                            // 的 GameMessage{Encoder,Decoder} 帧格式严格一致）。
                            p.addLast("binaryDecoder", new ClientMessageDecoder());
                            p.addLast("binaryEncoder", new ClientMessageEncoder());
                        }
                        p.addLast("inbound", handler);
                    }
                });
        ChannelFuture f = b.connect(host, port).sync();
        this.channel = f.channel();
        if (mode == Transport.WS) {
            if (!handler.awaitHandshake(connectTimeout)) {
                throw new IllegalStateException("WebSocket handshake timed out after " + connectTimeout);
            }
        } else {
            // TCP 模式无 handshake；channel.isActive() 即视为就绪。
            handler.markTcpReady();
        }
        this.handshakeDone = true;
        if (mode == Transport.WS) {
            logger.info("Robot connected via WS to ws://{}:{}{}", host, port, path);
        } else {
            logger.info("Robot connected via TCP to {}:{}", host, port);
        }
    }

    /** 发起 AUTH（NEW path：不带 flowId）；阻塞等待 AuthResponse。 */
    public AuthResponse auth(String token, int gameId) throws Exception {
        return authInternal(token, gameId, null, 0L);
    }

    /** 发起 AUTH（RESUME path：带 flowId + lastClientRecvSeq）。 */
    public AuthResponse authResume(String token, int gameId, String flowId, long lastClientRecvSeq) throws Exception {
        return authInternal(token, gameId, flowId, lastClientRecvSeq);
    }

    private AuthResponse authInternal(String token, int gameId, String flowId, long lastSeq) throws Exception {
        ensureOpen();
        AuthRequest.Builder b = AuthRequest.newBuilder()
                .setToken(token)
                .setGameId(gameId)
                .setLastClientRecvSeq(lastSeq)
                .setClientFeatures(CLIENT_FEATURES);
        if (flowId != null && !flowId.isBlank()) {
            b.setFlowId(flowId);
        }
        int msgId = MessageRouteRegistry.getIdByName("AuthRequest");
        CompletableFuture<WrappedMessage> fut = handler.expect(
                MessageRouteRegistry.getIdByName("AuthRequest"));
        send(msgId, b.build().toByteArray());

        WrappedMessage wm = fut.get(receiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
        AuthResponse resp = AuthResponse.parseFrom(wm.getBody().toBytes());
        if (resp.getSuccess()) {
            this.flowId = resp.getFlowId();
        }
        return resp;
    }

    /** 发送一帧业务消息（不等回包）。 */
    public void send(int messageId, byte[] body) {
        ensureOpen();
        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(messageId);
        msg.getHeader().setMode(MessageHeader.MODE_REQUEST);
        msg.setBody(new RawMessageBody(body));
        channel.writeAndFlush(msg);
    }

    /** 阻塞等待指定 messageId 的下一帧；超时返回 empty。 */
    public Optional<WrappedMessage> awaitMessage(int messageId, Duration timeout) throws InterruptedException {
        ensureOpen();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long left = Math.max(0, deadline - System.nanoTime());
            WrappedMessage wm = handler.poll(Duration.ofNanos(left));
            if (wm == null) return Optional.empty();
            if (wm.getHeader().getMessageId() == messageId) {
                return Optional.of(wm);
            }
        }
        return Optional.empty();
    }

    /** 阻塞等待第一个满足 predicate 的帧；超时返回 empty。 */
    public Optional<WrappedMessage> awaitMessage(Predicate<WrappedMessage> filter, Duration timeout)
            throws InterruptedException {
        ensureOpen();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            long left = Math.max(0, deadline - System.nanoTime());
            WrappedMessage wm = handler.poll(Duration.ofNanos(left));
            if (wm == null) return Optional.empty();
            if (filter.test(wm)) {
                return Optional.of(wm);
            }
        }
        return Optional.empty();
    }

    /**
     * 注册事件型订阅：每次 inbound 收到指定 messageId 都会异步触发 callback。
     * <p>用于 S07 并发场景：N 个 robot 各自累计 HeartbeatAck 数量但不阻塞 main 测试线程。
     */
    public void subscribe(int messageId, Consumer<WrappedMessage> callback) {
        handler.subscribe(messageId, callback);
    }

    /** 取出当前已收到的所有帧快照（不阻塞）。 */
    public List<WrappedMessage> drainInbox() {
        return handler.drain();
    }

    /** 等待 channel 进入 inactive 状态（适合 S04 被 evict 后验证）；超时返回 false。 */
    public boolean awaitInactive(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (channel == null || !channel.isActive()) return true;
            Thread.sleep(20);
        }
        return false;
    }

    /** 发送一次心跳。 */
    public void sendHeartbeat() {
        int msgId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
        ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                .setTimestamp(System.currentTimeMillis())
                .setLastClientRecvSeq(lastClientRecvSeq.get())
                .build();
        send(msgId, hb.toByteArray());
    }

    public String currentFlowId() { return flowId; }

    public long lastClientRecvSeq() { return lastClientRecvSeq.get(); }

    public boolean isOpen() { return channel != null && channel.isOpen(); }

    /** 强制关闭底层 TCP 连接，模拟弱网；group 不释放，可后续 {@link #connect()} 复用。 */
    public void abruptClose() {
        if (channel != null && channel.isOpen()) {
            channel.close().awaitUninterruptibly(receiveTimeout.toMillis());
        }
    }

    @Override
    public void close() {
        try {
            abruptClose();
        } finally {
            if (group != null && !shareEventLoop) {
                group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            }
        }
    }

    private void ensureOpen() {
        if (channel == null || !channel.isOpen() || !handshakeDone) {
            throw new IllegalStateException("Robot not connected; call connect() first");
        }
    }

    // ==================== inbound handler ====================

    private final class RobotInboundHandler extends SimpleChannelInboundHandler<WrappedMessage> {

        private final BlockingQueue<WrappedMessage> inbox = new LinkedBlockingQueue<>();
        private final CopyOnWriteArrayList<PendingExpect> pending = new CopyOnWriteArrayList<>();
        private final ConcurrentMap<Integer, CopyOnWriteArrayList<Consumer<WrappedMessage>>> subscribers
                = new ConcurrentHashMap<>();
        private final CompletableFuture<Void> handshakeFuture = new CompletableFuture<>();

        boolean awaitHandshake(Duration timeout) {
            try {
                handshakeFuture.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** TCP 模式无 handshake；直接置位让 awaitHandshake 立刻返回 true。 */
        void markTcpReady() {
            handshakeFuture.complete(null);
        }

        CompletableFuture<WrappedMessage> expect(int messageId) {
            CompletableFuture<WrappedMessage> f = new CompletableFuture<>();
            pending.add(new PendingExpect(messageId, f));
            return f;
        }

        WrappedMessage poll(Duration timeout) throws InterruptedException {
            return inbox.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        }

        List<WrappedMessage> drain() {
            List<WrappedMessage> out = new java.util.ArrayList<>(inbox.size());
            inbox.drainTo(out);
            return out;
        }

        void subscribe(int messageId, Consumer<WrappedMessage> callback) {
            subscribers.computeIfAbsent(messageId, k -> new CopyOnWriteArrayList<>()).add(callback);
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent ev
                    && ev == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                handshakeFuture.complete(null);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WrappedMessage msg) {
            MessageHeader h = msg.getHeader();
            if (h.hasGwSeq() && h.getGwSeq() > lastClientRecvSeq.get()) {
                lastClientRecvSeq.set(h.getGwSeq());
            }
            // 1) 事件订阅者：所有都触发，不消费帧
            CopyOnWriteArrayList<Consumer<WrappedMessage>> subs = subscribers.get(h.getMessageId());
            if (subs != null) {
                for (Consumer<WrappedMessage> c : subs) {
                    try {
                        c.accept(msg);
                    } catch (Throwable t) {
                        logger.warn("Robot subscriber threw: {}", t.toString());
                    }
                }
            }
            // 2) 优先派发给最早 expect 同 messageId 的等待者
            for (PendingExpect p : pending) {
                if (p.messageId == h.getMessageId() && !p.future.isDone()) {
                    p.future.complete(msg);
                    pending.remove(p);
                    return;
                }
            }
            inbox.offer(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            for (PendingExpect p : pending) {
                if (!p.future.isDone()) {
                    p.future.completeExceptionally(new IllegalStateException("channel inactive before reply"));
                }
            }
            pending.clear();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.warn("Robot pipeline exception: {}", cause.toString());
            ctx.close();
        }
    }

    private record PendingExpect(int messageId, CompletableFuture<WrappedMessage> future) {}

    // ==================== Builder ====================

    public static final class Builder {
        private String host = "127.0.0.1";
        private int port = 8888;
        private Transport transport = Transport.WS;
        private String path = "/ws";
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Duration receiveTimeout = Duration.ofSeconds(5);
        private boolean shareEventLoop = false;

        public Builder host(String h) { this.host = h; return this; }
        /** 设置 WS 端口（向后兼容：等价 {@code port(p).transport(WS)}）。 */
        public Builder wsPort(int p) {
            this.port = p;
            this.transport = Transport.WS;
            return this;
        }
        /** 设置 TCP 端口；同时把 transport 切到 {@link Transport#TCP}。 */
        public Builder tcpPort(int p) {
            this.port = p;
            this.transport = Transport.TCP;
            return this;
        }
        /** 显式指定 transport；与 {@link #wsPort(int)} / {@link #tcpPort(int)} 互斥。 */
        public Builder transport(Transport t) { this.transport = t; return this; }
        /** 显式指定端口（不改变 transport）。 */
        public Builder port(int p) { this.port = p; return this; }
        public Builder path(String p) { this.path = p; return this; }
        public Builder connectTimeout(Duration d) { this.connectTimeout = d; return this; }
        public Builder receiveTimeout(Duration d) { this.receiveTimeout = d; return this; }

        public RobotClient build() {
            return new RobotClient(this);
        }
    }

    /** 调试用：返回构建/连接时间戳，便于报告打印。 */
    public Instant nowUtc() {
        return Instant.now();
    }
}

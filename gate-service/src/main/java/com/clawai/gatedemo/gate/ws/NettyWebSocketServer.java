package com.clawai.gatedemo.gate.ws;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.config.TlsSslContextFactory;
import com.clawai.gatedemo.gate.handler.GateNettyWebSocketHandler;
import com.clawai.gatedemo.gate.transport.GatewayTransport;
import com.clawai.gatedemo.gate.transport.TransportInfo;
import com.clawai.gatedemo.gate.transport.TransportState;
import com.clawai.gatedemo.gate.ws.codec.WebSocketBinaryDecoder;
import com.clawai.gatedemo.gate.ws.codec.WebSocketBinaryEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty WebSocket 服务器 - Spring管理的服务。
 *
 * <p>B4：实现 {@link GatewayTransport}，纳入 {@link com.clawai.gatedemo.gate.transport.GatewayTransportRegistry}
 * 统一管理，{@link #start()} 与 {@link #stop()} 现在均幂等。
 *
 * 职责：
 * 1. 启动Netty服务器，监听WebSocket端口
 * 2. 配置ChannelPipeline
 * 3. 管理Boss和Worker线程组
 *
 * Spring集成：
 * - @Component: 自动注册为Spring Bean
 * - @PostConstruct: 启动服务器
 * - @PreDestroy: 停止服务器
 */
@Component
public class NettyWebSocketServer implements GatewayTransport {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);
    private static final String TRANSPORT_NAME = "websocket";

    /** Gate配置 */
    private final GateConfig gateConfig;

    /** 玩家连接处理器 */
    private final GateNettyWebSocketHandler gateWebSocketHandler;

    /** Boss 线程组 */
    private EventLoopGroup bossGroup;

    /** Worker 线程组 */
    private EventLoopGroup workerGroup;

    /** Netty 服务器 Channel */
    private Channel serverChannel;

    /** 启用 WSS 时非空；纯 WS 时为 null */
    private SslContext sslContext;

    /** 生命周期状态机（{@link GatewayTransport}）。 */
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.NEW);

    /**
     * 构造函数 - Spring自动注入依赖
     */
    public NettyWebSocketServer(GateConfig gateConfig, GateNettyWebSocketHandler gateWebSocketHandler) {
        this.gateConfig = gateConfig;
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    @Override
    public String name() {
        return TRANSPORT_NAME;
    }

    /** WebSocket 接入始终开启（作为默认主入口），由 server.port 控制。 */
    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public TransportState state() {
        return state.get();
    }

    @Override
    public TransportInfo info() {
        String scheme = gateConfig.getTls().isEnabled() ? "wss" : "ws";
        return new TransportInfo(TRANSPORT_NAME, gateConfig.getHost(), gateConfig.getPort(), scheme);
    }

    /**
     * 启动服务器 - Spring容器初始化完成后自动调用。
     * <p>幂等：若已 {@link TransportState#RUNNING} 则直接返回，避免重复 bind 同一端口。
     */
    @Override
    @PostConstruct
    public void start() {
        if (!state.compareAndSet(TransportState.NEW, TransportState.STARTING)
                && !state.compareAndSet(TransportState.STOPPED, TransportState.STARTING)) {
            logger.debug("NettyWebSocketServer.start() ignored, current state={}", state.get());
            return;
        }
        logger.info("Starting Netty WebSocket server on port {}", gateConfig.getPort());

        if (gateConfig.getTls().isEnabled()) {
            try {
                sslContext = TlsSslContextFactory.buildServerContext(gateConfig.getTls());
                logger.info("TLS enabled for WebSocket (wss://)");
            } catch (Exception e) {
                state.set(TransportState.STOPPED);
                throw new RuntimeException("Failed to initialize TLS for WebSocket", e);
            }
        }

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (sslContext != null) {
                            pipeline.addLast("ssl", sslContext.newHandler(ch.alloc()));
                        }
                        pipeline.addLast("httpCodec", new HttpServerCodec());
                        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));
                        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler("/ws"));
                        pipeline.addLast("wsBinaryDecoder", new WebSocketBinaryDecoder());
                        pipeline.addLast("wsBinaryEncoder", new WebSocketBinaryEncoder());
                        pipeline.addLast("idleState", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
                        pipeline.addLast("businessHandler", gateWebSocketHandler);
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            ChannelFuture future = bootstrap.bind(gateConfig.getPort()).sync();
            serverChannel = future.channel();
            state.set(TransportState.RUNNING);

            String scheme = sslContext != null ? "wss" : "ws";
            logger.info("WebSocket server started: {}://0.0.0.0:{}/ws", scheme, gateConfig.getPort());

        } catch (Exception e) {
            state.set(TransportState.STOPPED);
            logger.error("Failed to start WebSocket server: {}", e.getMessage(), e);
            throw new RuntimeException("WebSocket server start failed", e);
        }
    }

    /**
     * 停止接受新连接（优雅关闭阶段一）
     * 关闭 server channel 后不再接受新的 TCP 连接
     */
    @Override
    public void stopAccepting() {
        if (state.compareAndSet(TransportState.RUNNING, TransportState.STOPPING_ACCEPT)) {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
                logger.info("WebSocket 服务器已停止接受新连接");
            }
        }
    }

    /**
     * 停止服务器 - Spring容器关闭前自动调用。
     * <p>幂等：若已 {@link TransportState#STOPPED} 则直接返回。
     */
    @Override
    @PreDestroy
    public void stop() {
        TransportState prev = state.getAndSet(TransportState.STOPPING);
        if (prev == TransportState.STOPPED) {
            state.set(TransportState.STOPPED);
            return;
        }
        logger.info("=== 开始关闭 Netty WebSocket 服务器 ===");

        if (serverChannel != null) {
            serverChannel.close();
            logger.info("服务器 Channel 已关闭");
        }

        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            logger.info("Boss 线程组已关闭");
        }

        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            logger.info("Worker 线程组已关闭");
        }

        state.set(TransportState.STOPPED);
        logger.info("✅ Netty WebSocket 服务器已完全关闭");
    }
}

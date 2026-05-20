package com.clawai.gatedemo.gate.tcp;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder;
import com.clawai.gatedemo.gate.tcp.heartbeat.TcpHeartbeatHandler;
import com.clawai.gatedemo.gate.transport.GatewayTransport;
import com.clawai.gatedemo.gate.transport.TransportInfo;
import com.clawai.gatedemo.gate.transport.TransportState;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty TCP 接入：{@code gate.tcp.enabled=true} 时监听 {@code gate.tcp.port}，复用二进制编解码与 {@link TcpMessageHandler}。
 *
 * <p>B4：实现 {@link GatewayTransport}，纳入 {@link com.clawai.gatedemo.gate.transport.GatewayTransportRegistry}。
 * {@link #start()} / {@link #stop()} 幂等。
 */
@Component
@ConditionalOnProperty(name = "gate.tcp.enabled", havingValue = "true")
public class NettyTcpServer implements GatewayTransport {

    private static final Logger logger = LoggerFactory.getLogger(NettyTcpServer.class);
    private static final String TRANSPORT_NAME = "tcp";

    private final GateConfig gateConfig;
    private final TcpMessageHandler tcpMessageHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.NEW);

    /**
     * @param gateConfig        TCP 端口等
     * @param tcpMessageHandler 业务入站处理
     */
    public NettyTcpServer(GateConfig gateConfig, TcpMessageHandler tcpMessageHandler) {
        this.gateConfig = gateConfig;
        this.tcpMessageHandler = tcpMessageHandler;
    }

    @Override
    public String name() {
        return TRANSPORT_NAME;
    }

    @Override
    public boolean isEnabled() {
        return gateConfig.getTcp().isEnabled();
    }

    @Override
    public TransportState state() {
        return state.get();
    }

    @Override
    public TransportInfo info() {
        return new TransportInfo(TRANSPORT_NAME, gateConfig.getHost(), gateConfig.getTcp().getPort(), "tcp");
    }

    /**
     * 同步 bind 端口；pipeline 含读空闲、心跳、编解码与业务 handler。
     * <p>幂等：若已 {@link TransportState#RUNNING} 则直接返回。
     *
     * @throws InterruptedException {@link ChannelFuture#sync()} 被中断
     */
    @Override
    @PostConstruct
    public void start() throws InterruptedException {
        if (!state.compareAndSet(TransportState.NEW, TransportState.STARTING)
                && !state.compareAndSet(TransportState.STOPPED, TransportState.STARTING)) {
            logger.debug("NettyTcpServer.start() ignored, current state={}", state.get());
            return;
        }
        int port = gateConfig.getTcp().getPort();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS));
                            pipeline.addLast(new TcpHeartbeatHandler(60));
                            pipeline.addLast(new GameMessageDecoder());
                            pipeline.addLast(new GameMessageEncoder());
                            pipeline.addLast(tcpMessageHandler);
                        }
                    });

            ChannelFuture future = bootstrap.bind(port).sync();
            serverChannel = future.channel();
            state.set(TransportState.RUNNING);
            logger.info("TCP Server started on port {}", port);
        } catch (Exception e) {
            state.set(TransportState.STOPPED);
            throw e;
        }
    }

    /** 关闭服务端 Channel，但保留已有连接。 */
    @Override
    public void stopAccepting() {
        if (state.compareAndSet(TransportState.RUNNING, TransportState.STOPPING_ACCEPT)) {
            if (serverChannel != null && serverChannel.isOpen()) {
                serverChannel.close();
                logger.info("TCP 服务器已停止接受新连接");
            }
        }
    }

    /** 关闭服务端 Channel 与线程组（幂等）。 */
    @Override
    @PreDestroy
    public void stop() {
        TransportState prev = state.getAndSet(TransportState.STOPPING);
        if (prev == TransportState.STOPPED) {
            state.set(TransportState.STOPPED);
            return;
        }
        if (serverChannel != null) {
            serverChannel.close();
            serverChannel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        state.set(TransportState.STOPPED);
        logger.info("TCP Server stopped");
    }
}

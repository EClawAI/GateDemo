package com.clawai.gatedemo.gate.tcp;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder;
import com.clawai.gatedemo.gate.tcp.heartbeat.TcpHeartbeatHandler;
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

/**
 * Netty TCP Server - Spring-managed component with enable/disable switch.
 * Listens on gate.tcp.port when gate.tcp.enabled is true.
 * Reuses binary protocol handlers (GameMessageDecoder/Encoder, TcpMessageHandler).
 */
@Component
@ConditionalOnProperty(name = "gate.tcp.enabled", havingValue = "true")
public class NettyTcpServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyTcpServer.class);

    private final GateConfig gateConfig;
    private final TcpMessageHandler tcpMessageHandler;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyTcpServer(GateConfig gateConfig, TcpMessageHandler tcpMessageHandler) {
        this.gateConfig = gateConfig;
        this.tcpMessageHandler = tcpMessageHandler;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        int port = gateConfig.getTcp().getPort();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

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
        logger.info("TCP Server started on port {}", port);
    }

    @PreDestroy
    public void stop() {
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
        logger.info("TCP Server stopped");
    }
}

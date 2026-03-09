package com.clawai.gatedemo.gate.ws;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.handler.GateNettyWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务器 - Spring管理的服务
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
public class NettyWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);

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

    /**
     * 构造函数 - Spring自动注入依赖
     */
    public NettyWebSocketServer(GateConfig gateConfig, GateNettyWebSocketHandler gateWebSocketHandler) {
        this.gateConfig = gateConfig;
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    /**
     * 启动服务器 - Spring容器初始化完成后自动调用
     */
    @PostConstruct
    public void start() {
        logger.info("=== 开始启动 Netty WebSocket 服务器 ===");
        logger.info("监听端口：{}", gateConfig.getPort());

        // 1. 创建 Boss 线程组
        bossGroup = new NioEventLoopGroup(1);
        logger.info("Boss 线程组已创建，线程数：1");

        // 2. 创建 Worker 线程组
        workerGroup = new NioEventLoopGroup();
        logger.info("Worker 线程组已创建");

        try {
            // 3. 创建和配置服务器
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast("httpCodec", new HttpServerCodec());
                        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));
                        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler("/ws"));
                        pipeline.addLast("idleState", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));
                        pipeline.addLast("businessHandler", gateWebSocketHandler);
                        logger.info("新连接 Pipeline 初始化完成：{}", ch.remoteAddress());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            // 4. 绑定端口并启动
            ChannelFuture future = bootstrap.bind(gateConfig.getPort()).sync();
            serverChannel = future.channel();

            logger.info("===========================================");
            logger.info("✅ Netty WebSocket 服务器启动成功！");
            logger.info("监听地址：0.0.0.0:{}", gateConfig.getPort());
            logger.info("WebSocket 路径：/ws");
            logger.info("完整地址：ws://localhost:{}/ws", gateConfig.getPort());
            logger.info("===========================================");

        } catch (Exception e) {
            logger.error("❌ Netty WebSocket 服务器启动失败：{}", e.getMessage(), e);
            throw new RuntimeException("Netty WebSocket 服务器启动失败", e);
        }
    }

    /**
     * 停止服务器 - Spring容器关闭前自动调用
     */
    @PreDestroy
    public void stop() {
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

        logger.info("✅ Netty WebSocket 服务器已完全关闭");
    }
}

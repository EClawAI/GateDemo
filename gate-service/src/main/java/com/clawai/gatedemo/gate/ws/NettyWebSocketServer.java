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

import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务器 - 负责启动和管理WebSocket服务
 *
 * 职责：
 * 1. 启动Netty服务器，监听WebSocket端口
 * 2. 配置ChannelPipeline
 * 3. 管理Boss和Worker线程组
 *
 * 与NettyWebSocketServerConfig的关系：
 * - NettyWebSocketServer：纯服务类，只管启动/停止
 * - NettyWebSocketServerConfig：Spring配置类，注入依赖并调用服务
 *
 * 设计原则：
 * - 单一职责：服务器生命周期由专门类管理
 * - 依赖注入：Handler通过构造函数注入，便于测试
 * - 优雅关闭：确保资源正确释放
 */
public class NettyWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);

    /** Gate配置 */
    private final GateConfig gateConfig;

    /** 玩家连接处理器 */
    private final GateNettyWebSocketHandler gateWebSocketHandler;

    /** Boss 线程组 - 负责接受客户端连接 */
    private EventLoopGroup bossGroup;

    /** Worker 线程组 - 负责处理 IO 读写 */
    private EventLoopGroup workerGroup;

    /** Netty 服务器 Channel */
    private Channel serverChannel;

    /**
     * 构造函数
     * 
     * @param gateConfig Gate配置
     * @param gateWebSocketHandler 玩家连接处理器
     */
    public NettyWebSocketServer(GateConfig gateConfig, GateNettyWebSocketHandler gateWebSocketHandler) {
        this.gateConfig = gateConfig;
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    /**
     * 启动 WebSocket 服务器
     * 
     * 启动流程：
     * 1. 创建 Boss 和 Worker 线程组
     * 2. 配置 ServerBootstrap（服务器引导类）
     * 3. 设置 ChannelPipeline（处理链）
     * 4. 绑定端口并启动
     */
    public void start() {
        logger.info("=== 开始启动 Netty WebSocket 服务器 ===");
        logger.info("监听端口：{}", gateConfig.getPort());

        // 1. 创建 Boss 线程组（接受连接）
        bossGroup = new NioEventLoopGroup(1);
        logger.info("Boss 线程组已创建，线程数：1");

        // 2. 创建 Worker 线程组（处理 IO）
        workerGroup = new NioEventLoopGroup();
        logger.info("Worker 线程组已创建");

        try {
            // 3. 创建服务器引导类
            ServerBootstrap bootstrap = new ServerBootstrap();

            // 4. 配置引导类
            bootstrap
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        // === HTTP 协议处理器 ===
                        pipeline.addLast("httpCodec", new HttpServerCodec());
                        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));

                        // === WebSocket 协议处理器 ===
                        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler("/ws"));

                        // 空闲状态处理器（心跳检测）
                        pipeline.addLast("idleState", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));

                        // === 业务处理器 ===
                        pipeline.addLast("businessHandler", gateWebSocketHandler);

                        logger.info("新连接 Pipeline 初始化完成：{}", ch.remoteAddress());
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

            logger.info("ServerBootstrap 配置完成");

            // 5. 绑定端口并启动
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
     * 停止 WebSocket 服务器
     * 
     * 关闭流程：
     * 1. 关闭服务器 Channel
     * 2. 优雅关闭 Boss 线程组
     * 3. 优雅关闭 Worker 线程组
     */
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

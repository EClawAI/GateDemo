package com.clawai.gatedemo.gate.config;

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
 * Netty WebSocket 服务器配置（纯 Netty 实现）
 * 
 * 功能说明：
 * 1. 使用 Netty 创建独立的 WebSocket 服务器
 * 2. 监听独立端口（默认 8888）
 * 3. 支持 WebSocket 协议升级（HTTP → WebSocket）
 * 4. 处理玩家连接、消息收发、心跳检测
 * 
 * 架构说明：
 * ┌─────────────────────────────────────────────────┐
 * │           Netty WebSocket Server                │
 * │                                                 │
 * │  Boss Group (1 个线程)                           │
 * │    ↓ 接受连接                                    │
 * │  Worker Group (多个线程)                         │
 * │    ↓ 处理 IO                                     │
 * │  ChannelPipeline                                 │
 * │    ├─ HttpServerCodec        - HTTP 编解码器     │
 * │    ├─ HttpObjectAggregator   - HTTP 消息聚合     │
 * │    ├─ ChunkedWriteHandler    - 大数据流写入      │
 * │    ├─ WebSocketServerProtocolHandler - WS 协议   │
 * │    └─ GateNettyWebSocketHandler - 业务处理       │
 * └─────────────────────────────────────────────────┘
 * 
 * @author clawAI
 * @since 2026-03-05
 */
public class NettyWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);

    /**
     * WebSocket 服务器端口
     */
    private final int webSocketPort;

    /**
     * 玩家连接处理器
     */
    private final GateNettyWebSocketHandler gateWebSocketHandler;

    /**
     * Boss 线程组 - 负责接受客户端连接
     */
    private EventLoopGroup bossGroup;

    /**
     * Worker 线程组 - 负责处理 IO 读写
     */
    private EventLoopGroup workerGroup;

    /**
     * Netty 服务器 Channel
     */
    private Channel serverChannel;

    /**
     * 构造函数
     * 
     * @param webSocketPort WebSocket 端口
     * @param gateWebSocketHandler 玩家连接处理器
     */
    public NettyWebSocketServer(int webSocketPort, GateNettyWebSocketHandler gateWebSocketHandler) {
        this.webSocketPort = webSocketPort;
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
        logger.info("监听端口：{}", webSocketPort);

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
            ChannelFuture future = bootstrap.bind(webSocketPort).sync();
            serverChannel = future.channel();

            logger.info("===========================================");
            logger.info("✅ Netty WebSocket 服务器启动成功！");
            logger.info("监听地址：0.0.0.0:{}", webSocketPort);
            logger.info("WebSocket 路径：/ws");
            logger.info("完整地址：ws://localhost:{}/ws", webSocketPort);
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
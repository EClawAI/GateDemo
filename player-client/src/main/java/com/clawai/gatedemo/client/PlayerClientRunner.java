package com.clawai.gatedemo.client;

import com.clawai.gatedemo.client.config.PlayerClientConfig;
import com.clawai.gatedemo.client.handler.PlayerNettyHandler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Player Client 启动器
 * 
 * 功能说明：
 * 1. 使用 Netty 创建 WebSocket 客户端
 * 2. 连接到 Gate 服务
 * 3. 自动认证和心跳
 * 
 * @author clawAI
 * @since 2026-03-05
 */
public class PlayerClientRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientRunner.class);

    private final PlayerClientConfig config;
    private final PlayerNettyHandler handler;

    public PlayerClientRunner(PlayerClientConfig config, PlayerNettyHandler handler) {
        this.config = config;
        this.handler = handler;
    }

    @Override
    public void run(String... args) {
        connect();
    }

    /**
     * 连接到 Gate 服务
     */
    public void connect() {
        logger.info("=== 开始连接 Gate 服务 ===");
        logger.info("服务器：{}:{}", config.getHost(), config.getPort());
        logger.info("玩家 ID: {}", config.getPlayerId());

        // 1. 创建 IO 线程组
        // NioEventLoopGroup 基于 Java NIO，使用多路复用技术
        // 一个线程可以处理成千上万个连接
        config.setGroup(new NioEventLoopGroup());

        try {
            // 2. 创建客户端引导类
            // Bootstrap 是 Netty 提供的客户端启动辅助类
            Bootstrap bootstrap = new Bootstrap();

            // 3. 配置引导类
            bootstrap
                // 设置 IO 线程组
                .group(config.getGroup())
                // 设置 Channel 类型
                // NioSocketChannel 基于 NIO，适用于 TCP 客户端
                .channel(NioSocketChannel.class)
                // 设置连接超时
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                // 设置 Channel 初始化器
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 获取 ChannelPipeline（处理链）
                        ChannelPipeline pipeline = ch.pipeline();

                        // === 添加 HTTP 协议处理器 ===
                        
                        // HttpClientCodec：HTTP 编解码器
                        // 用于 WebSocket 握手（HTTP 升级）
                        pipeline.addLast("httpCodec", new HttpClientCodec());

                        // HttpObjectAggregator：HTTP 消息聚合器
                        // 将分片的 HTTP 消息聚合成完整的 FullHttpResponse
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));

                        // === 添加 WebSocket 协议处理器 ===
                        
                        // 配置 WebSocket 客户端协议
                        WebSocketClientProtocolConfig wsConfig = WebSocketClientProtocolConfig.newBuilder()
                            .webSocketUri("ws://" + config.getHost() + ":" + config.getPort() + "/ws")
                            .build();

                        // WebSocketClientProtocolHandler：WebSocket 客户端协议处理器
                        // 处理 WebSocket 握手（发送 Upgrade 请求）
                        // 处理 WebSocket 帧的编解码
                        pipeline.addLast("wsProtocol", new WebSocketClientProtocolHandler(wsConfig));

                        // 空闲状态处理器（心跳检测）
                        // 当超过指定时间没有写操作时，触发 UserEventTriggered 事件
                        pipeline.addLast("idleState", new IdleStateHandler(0, config.getHeartbeatInterval(), 0, TimeUnit.SECONDS));

                        // === 添加业务处理器 ===
                        
                        // PlayerNettyHandler：玩家客户端业务处理器
                        // 处理认证、心跳、游戏消息等
                        pipeline.addLast("businessHandler", handler);

                        logger.info("Pipeline 初始化完成");
                    }
                });

            // 4. 连接服务器
            // sync() 方法会阻塞直到连接完成
            ChannelFuture future = bootstrap.connect(config.getHost(), config.getPort()).sync();
            config.setChannel(future.channel());

            logger.info("===========================================");
            logger.info("✅ 已连接到 Gate 服务！");
            logger.info("===========================================");

            // 5. 等待 Channel 关闭
            config.getChannel().closeFuture().sync();

        } catch (Exception e) {
            logger.error("❌ 连接失败：{}", e.getMessage());
        } finally {
            // 6. 优雅关闭线程组
            if (config.getGroup() != null) {
                config.getGroup().shutdownGracefully();
            }
            logger.info("客户端已关闭");
        }
    }
}
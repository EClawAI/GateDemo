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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 服务器配置
 * 
 * 功能说明：
 * 1. 使用 Netty 创建独立的 WebSocket 服务器（不依赖 Spring WebSocket）
 * 2. 监听独立端口（默认 8888），与 HTTP 端口（8083）分离
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
@Component
public class NettyWebSocketServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServerConfig.class);

    /**
     * WebSocket 服务器端口，从配置文件读取
     * 默认值：8888
     * 可通过环境变量 GATE_PORT 覆盖
     */
    @Value("${gate.port:8888}")
    private int webSocketPort;

    /**
     * 玩家连接处理器（由 Spring 自动注入）
     * 负责处理玩家认证、消息转发等业务逻辑
     */
    private final GateNettyWebSocketHandler gateWebSocketHandler;

    /**
     * Boss 线程组 - 负责接受客户端连接
     * 通常只需要 1 个线程，因为只处理 Accept 事件
     */
    private EventLoopGroup bossGroup;

    /**
     * Worker 线程组 - 负责处理 IO 读写
     * 线程数默认 = CPU 核心数 * 2
     */
    private EventLoopGroup workerGroup;

    /**
     * Netty 服务器 Channel
     * 代表绑定的服务器端口
     */
    private Channel serverChannel;

    /**
     * 构造函数，注入玩家连接处理器
     * 
     * @param gateWebSocketHandler 玩家连接处理器
     */
    public NettyWebSocketServerConfig(GateNettyWebSocketHandler gateWebSocketHandler) {
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    /**
     * 启动 WebSocket 服务器
     * 
     * 在 Spring 容器初始化完成后自动调用
     * 
     * 启动流程：
     * 1. 创建 Boss 和 Worker 线程组
     * 2. 配置 ServerBootstrap（服务器引导类）
     * 3. 设置 ChannelPipeline（处理链）
     * 4. 绑定端口并启动
     * 5. 等待服务器关闭
     */
    @PostConstruct
    public void start() {
        logger.info("=== 开始启动 Netty WebSocket 服务器 ===");
        logger.info("监听端口：{}", webSocketPort);

        // 1. 创建 Boss 线程组（接受连接）
        // NioEventLoopGroup 基于 Java NIO，使用多路复用技术
        // 一个线程可以处理成千上万个连接
        bossGroup = new NioEventLoopGroup(1);
        logger.info("Boss 线程组已创建，线程数：1");

        // 2. 创建 Worker 线程组（处理 IO）
        // 不指定线程数时，默认为 CPU 核心数 * 2
        workerGroup = new NioEventLoopGroup();
        logger.info("Worker 线程组已创建，线程数：{}", workerGroup.executorCount());

        try {
            // 3. 创建服务器引导类
            // ServerBootstrap 是 Netty 提供的服务器启动辅助类
            ServerBootstrap bootstrap = new ServerBootstrap();

            // 4. 配置引导类
            bootstrap
                // 设置 Boss 和 Worker 线程组
                .group(bossGroup, workerGroup)
                // 设置 Channel 类型
                // NioServerSocketChannel 基于 NIO，适用于 TCP 服务器
                .channel(NioServerSocketChannel.class)
                // 设置子 Channel 初始化器（每个新连接都会调用）
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // 获取 ChannelPipeline（处理链）
                        // Pipeline 中的 Handler 按顺序处理入站和出站事件
                        ChannelPipeline pipeline = ch.pipeline();

                        // === 添加 HTTP 协议处理器 ===
                        
                        // HttpServerCodec：HTTP 编解码器
                        // 将字节流解码为 HttpRequest/HttpResponse 对象
                        // 将 HttpRequest/HttpResponse 对象编码为字节流
                        pipeline.addLast("httpCodec", new HttpServerCodec());

                        // ChunkedWriteHandler：分块写入处理器
                        // 支持大数据流的流式传输，避免一次性加载到内存
                        pipeline.addLast("chunkedWriter", new ChunkedWriteHandler());

                        // HttpObjectAggregator：HTTP 消息聚合器
                        // 将分片的 HTTP 消息（HttpRequest + HttpContent）聚合成完整的 FullHttpRequest
                        // 参数 8192 表示最大聚合 8KB 的数据
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));

                        // === 添加 WebSocket 协议处理器 ===
                        
                        // WebSocketServerProtocolHandler：WebSocket 协议处理器
                        // 处理 WebSocket 握手（HTTP → WebSocket 协议升级）
                        // 处理 WebSocket 帧（Frame）的编解码
                        // 参数 "/ws" 表示 WebSocket 路径，只有访问 /ws 才会升级协议
                        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler("/ws"));

                        // 空闲状态处理器（心跳检测）
                        // 参数：读空闲时间、写空闲时间、读写空闲时间（秒）
                        // 当超过指定时间没有读写操作时，会触发 UserEventTriggered 事件
                        pipeline.addLast("idleState", new IdleStateHandler(300, 0, 0, TimeUnit.SECONDS));

                        // === 添加业务处理器 ===
                        
                        // GateNettyWebSocketHandler：游戏网关业务处理器
                        // 处理玩家认证、消息收发、心跳等业务逻辑
                        pipeline.addLast("businessHandler", gateWebSocketHandler);

                        logger.info("新连接 Pipeline 初始化完成：{}", ch.remoteAddress());
                    }
                })
                // 设置 TCP 参数
                .option(ChannelOption.SO_BACKLOG, 128)  // Boss 线程的连接队列长度
                .childOption(ChannelOption.SO_KEEPALIVE, true);  // 开启 TCP KeepAlive

            logger.info("ServerBootstrap 配置完成");

            // 5. 绑定端口并启动服务器
            // sync() 方法会阻塞直到绑定完成
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
     * 在 Spring 容器关闭前自动调用
     * 
     * 关闭流程：
     * 1. 关闭服务器 Channel（停止接受新连接）
     * 2. 优雅关闭 Boss 线程组
     * 3. 优雅关闭 Worker 线程组
     * 
     * 优雅关闭意味着：
     * - 等待已连接的玩家完成当前操作
     * - 等待已提交的任务执行完成
     * - 然后才关闭线程组
     */
    @PreDestroy
    public void stop() {
        logger.info("=== 开始关闭 Netty WebSocket 服务器 ===");

        // 1. 关闭服务器 Channel
        if (serverChannel != null) {
            serverChannel.close();
            logger.info("服务器 Channel 已关闭");
        }

        // 2. 优雅关闭 Boss 线程组
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            logger.info("Boss 线程组已关闭");
        }

        // 3. 优雅关闭 Worker 线程组
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            logger.info("Worker 线程组已关闭");
        }

        logger.info("✅ Netty WebSocket 服务器已完全关闭");
    }
}
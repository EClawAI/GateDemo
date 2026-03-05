package com.clawai.gatedemo.client.config;

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
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 客户端配置
 * 
 * 功能说明：
 * 1. 使用 Netty 创建 WebSocket 客户端
 * 2. 连接到 Gate 服务的 WebSocket 端口
 * 3. 处理 WebSocket 握手和消息收发
 * 
 * 架构说明：
 * ┌─────────────────────────────────────────────────┐
 * │          Netty WebSocket Client                 │
 * │                                                 │
 * │  EventLoopGroup (IO 线程组)                      │
 * │    ↓                                             │
 * │  Bootstrap (客户端引导类)                        │
 * │    ↓                                             │
 * │  ChannelPipeline (处理链)                        │
 * │    ├─ HttpClientCodec         - HTTP 编解码器    │
 * │    ├─ HttpObjectAggregator    - HTTP 消息聚合    │
 * │    ├─ WebSocketClientProtocolHandler - WS 协议   │
 * │    └─ PlayerNettyHandler      - 业务处理         │
 * └─────────────────────────────────────────────────┘
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Component
@ConfigurationProperties(prefix = "player")
public class PlayerClientConfig {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientConfig.class);

    /**
     * 玩家 ID
     * 用于认证和消息标识
     */
    private Long playerId = 100001L;

    /**
     * Gate 服务器地址
     * 默认：localhost
     */
    private String host = "localhost";

    /**
     * Gate WebSocket 端口
     * 默认：8888
     */
    private int port = 8888;

    /**
     * 心跳间隔（秒）
     * 每隔多久发送一次心跳消息
     * 默认：30 秒
     */
    private int heartbeatInterval = 30;

    /**
     * IO 线程组
     * 负责处理所有 IO 操作（连接、读写、断开）
     */
    private EventLoopGroup group;

    /**
     * Netty Channel
     * 代表与服务器的连接
     */
    private Channel channel;

    // ==================== Getters and Setters ====================

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    public EventLoopGroup getGroup() { return group; }
    public void setGroup(EventLoopGroup group) { this.group = group; }
    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }
}
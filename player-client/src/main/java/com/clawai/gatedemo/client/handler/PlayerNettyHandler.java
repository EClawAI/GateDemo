package com.clawai.gatedemo.client.handler;

import com.clawai.gatedemo.client.config.PlayerClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Netty WebSocket 客户端业务处理器
 * 
 * 功能说明：
 * 1. 处理 WebSocket 握手完成事件
 * 2. 处理服务器发送的消息
 * 3. 处理连接断开和异常
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Component
public class PlayerNettyHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(PlayerNettyHandler.class);

    /**
     * 客户端配置（由 Spring 自动注入）
     */
    private final PlayerClientConfig config;

    /**
     * JSON 序列化工具（由 Spring 自动注入）
     */
    private final ObjectMapper objectMapper;

    /**
     * 连接是否已建立
     */
    private boolean connected = false;

    /**
     * 构造函数，注入依赖
     */
    public PlayerNettyHandler(PlayerClientConfig config, ObjectMapper objectMapper) {
        this.config = config;
        this.objectMapper = objectMapper;
    }

    /**
     * 通道激活回调
     * 
     * 当 TCP 连接建立后调用
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("📡 已连接到服务器：{}:{}", config.getHost(), config.getPort());
    }

    /**
     * 用户事件触发回调
     * 
     * 当发生特殊事件时调用
     * 如：WebSocket 握手完成
     * 
     * @param ctx Channel 上下文
     * @param evt 事件对象
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        // 判断是否是 WebSocket 握手完成事件
        if (evt instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent) {
            WebSocketClientProtocolHandler.ClientHandshakeStateEvent event = 
                (WebSocketClientProtocolHandler.ClientHandshakeStateEvent) evt;
            
            if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                // WebSocket 握手完成，可以开始发送消息
                logger.info("✅ WebSocket 握手完成");
                connected = true;
                
                // 自动发送认证消息
                sendAuth(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 通道读取回调
     * 
     * 当收到服务器消息时调用
     * 
     * @param ctx Channel 上下文
     * @param frame WebSocket 文本帧
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        logger.info("📥 收到消息：{}", text);

        // 解析 JSON 消息
        Map<String, Object> message = objectMapper.readValue(text, Map.class);
        String type = (String) message.get("type");

        // 根据消息类型处理
        if ("auth_ack".equals(type)) {
            logger.info("✅ 认证成功！玩家 ID: {}", message.get("player_id"));
        } else if ("heartbeat_ack".equals(type)) {
            logger.debug("💓 心跳响应收到");
        } else {
            logger.info("🎮 游戏消息：{}", message);
        }
    }

    /**
     * 通道断开回调
     * 
     * 当连接断开时调用
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("🔌 连接已断开");
        connected = false;
    }

    /**
     * 异常处理回调
     * 
     * 当发生异常时调用
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("❌ 连接异常：{}", cause.getMessage());
        ctx.close();
    }

    /**
     * 发送认证消息
     * 
     * @param ctx Channel 上下文
     */
    private void sendAuth(ChannelHandlerContext ctx) {
        try {
            Map<String, Object> authMessage = Map.of(
                "type", "auth",
                "player_id", config.getPlayerId()
            );
            String json = objectMapper.writeValueAsString(authMessage);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
            logger.info("📤 发送认证消息：playerId={}", config.getPlayerId());
        } catch (Exception e) {
            logger.error("❌ 发送认证失败：{}", e.getMessage());
        }
    }

    /**
     * 发送心跳消息
     * 
     * @param ctx Channel 上下文
     */
    public void sendHeartbeat(ChannelHandlerContext ctx) {
        if (!connected) {
            return;
        }
        try {
            Map<String, Object> heartbeat = Map.of(
                "type", "heartbeat",
                "player_id", config.getPlayerId()
            );
            String json = objectMapper.writeValueAsString(heartbeat);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
            logger.debug("💓 发送心跳");
        } catch (Exception e) {
            logger.error("❌ 发送心跳失败：{}", e.getMessage());
        }
    }

    /**
     * 发送游戏消息
     * 
     * @param ctx Channel 上下文
     * @param gameId 游戏 ID
     * @param msgType 消息类型
     * @param body 消息体
     */
    public void sendGameMessage(ChannelHandlerContext ctx, Integer gameId, String msgType, Map<String, Object> body) {
        if (!connected) {
            return;
        }
        try {
            Map<String, Object> message = Map.of(
                "type", "game_msg",
                "player_id", config.getPlayerId(),
                "game_id", gameId,
                "msg_type", msgType,
                "seq", System.currentTimeMillis(),
                "body", body
            );
            String json = objectMapper.writeValueAsString(message);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
            logger.info("🎮 发送游戏消息：gameId={}, type={}", gameId, msgType);
        } catch (Exception e) {
            logger.error("❌ 发送游戏消息失败：{}", e.getMessage());
        }
    }
}
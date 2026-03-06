package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Netty WebSocket 业务处理器（纯 Netty 实现）
 * 
 * 功能说明：
 * 1. 处理玩家 WebSocket 连接的生命周期（连接、断开）
 * 2. 处理玩家发送的文本消息（认证、心跳、游戏消息）
 * 3. 处理空闲超时事件（心跳检测）
 * 
 * 继承关系：
 * SimpleChannelInboundHandler<TextWebSocketFrame>
 *   ↓
 * ChannelInboundHandlerAdapter
 *   ↓
 * ChannelHandlerAdapter
 * 
 * SimpleChannelInboundHandler 特点：
 * - 自动释放消息引用（避免内存泄漏）
 * - 只处理指定类型的消息（TextWebSocketFrame）
 * - 处理完消息后自动从 Pipeline 中移除
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Component
@io.netty.channel.ChannelHandler.Sharable
public class GateNettyWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(GateNettyWebSocketHandler.class);

    /**
     * 玩家服务
     * 负责玩家注册、消息转发等业务逻辑
     */
    private final PlayerService playerService;

    /**
     * JSON 序列化工具
     * 用于将 Java 对象转换为 JSON 字符串，或反之
     */
    private final ObjectMapper objectMapper;

    /**
     * 构造函数
     * 
     * @param playerService 玩家服务
     * @param objectMapper JSON 序列化工具
     */
    public GateNettyWebSocketHandler(PlayerService playerService, ObjectMapper objectMapper) {
        this.playerService = playerService;
        this.objectMapper = objectMapper;
    }

    /**
     * 通道激活回调
     * 
     * 当客户端与服务端建立 TCP 连接后调用
     * 此时 WebSocket 握手还未完成
     * 
     * @param ctx Channel 上下文，包含 Channel、Pipeline 等信息
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("📡 新连接建立：{}", ctx.channel().remoteAddress());
    }

    /**
     * 通道读取回调
     * 
     * 当接收到客户端发送的消息时调用
     * 只处理 TextWebSocketFrame 类型的消息（文本消息）
     * 
     * @param ctx Channel 上下文
     * @param frame WebSocket 文本帧
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        try {
            // 1. 获取消息文本
            String text = frame.text();
            logger.debug("📥 收到消息：{}", text);

            // 2. 解析 JSON 消息
            PlayerMessage message = objectMapper.readValue(text, PlayerMessage.class);
            String type = message.getType();

            // 3. 根据消息类型处理
            if ("auth".equals(type)) {
                handleAuth(ctx, message);
            } else if ("heartbeat".equals(type)) {
                handleHeartbeat(ctx, message);
            } else if ("game_msg".equals(type)) {
                handleGameMessage(ctx, message);
            } else {
                logger.warn("⚠️ 未知消息类型：{} from {}", type, ctx.channel().remoteAddress());
            }
            
        } catch (Exception e) {
            logger.error("❌ 处理消息失败：{}", e.getMessage());
        }
    }

    /**
     * 处理认证消息
     * 
     * 认证流程：
     * 1. 解析 player_id
     * 2. 验证 player_id 是否合法
     * 3. 将 player_id 与 Channel 绑定
     * 4. 注册玩家到 PlayerService
     * 5. 发送认证成功响应
     * 
     * @param ctx Channel 上下文
     * @param message 认证消息
     */
    private void handleAuth(ChannelHandlerContext ctx, PlayerMessage message) {
        Long playerId = message.getPlayerId();
        
        // 1. 验证 player_id
        if (playerId == null || playerId <= 0) {
            logger.warn("❌ 认证失败：无效的 player_id from {}", ctx.channel().remoteAddress());
            ctx.close();
            return;
        }

        // 2. 检查是否重复登录
        if (playerService.hasPlayer(playerId)) {
            logger.warn("⚠️ 玩家 {} 已登录，关闭新连接", playerId);
            ctx.close();
            return;
        }

        // 3. 将 player_id 绑定到 Channel
        ctx.channel().attr(PlayerService.PLAYER_ID_KEY).set(playerId);

        // 4. 注册玩家
        playerService.registerPlayer(playerId, ctx.channel());
        logger.info("✅ 玩家 {} 认证成功", playerId);

        // 5. 发送认证成功响应
        PlayerMessage response = new PlayerMessage();
        response.setType("auth_ack");
        response.setPlayerId(playerId);
        response.setTimestamp(System.currentTimeMillis());
        sendToClient(ctx, response);
    }

    /**
     * 处理心跳消息
     * 
     * @param ctx Channel 上下文
     * @param message 心跳消息
     */
    private void handleHeartbeat(ChannelHandlerContext ctx, PlayerMessage message) {
        Long playerId = message.getPlayerId();
        
        if (playerId != null) {
            playerService.renewHeartbeat(playerId);
            
            PlayerMessage response = new PlayerMessage();
            response.setType("heartbeat_ack");
            response.setPlayerId(playerId);
            response.setTimestamp(System.currentTimeMillis());
            sendToClient(ctx, response);
            
            logger.debug("💓 玩家 {} 心跳续期", playerId);
        }
    }

    /**
     * 处理游戏消息
     * 
     * @param ctx Channel 上下文
     * @param message 游戏消息
     */
    private void handleGameMessage(ChannelHandlerContext ctx, PlayerMessage message) {
        Long playerId = message.getPlayerId();
        Integer gameId = message.getGameId();
        
        if (playerId != null && gameId != null) {
            playerService.forwardToGame(playerId, gameId, message);
            logger.debug("🎮 游戏消息转发：playerId={}, gameId={}, msgType={}", 
                playerId, gameId, message.getMsgType());
        } else {
            logger.warn("⚠️ 游戏消息缺少 playerId 或 gameId: {}", message);
        }
    }

    /**
     * 通道空闲回调
     * 
     * @param ctx Channel 上下文
     * @param evt 事件对象
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            if (event.state() == IdleState.READER_IDLE) {
                // 读空闲：超过 300 秒没有收到玩家消息
                logger.warn("⏰ 玩家心跳超时，关闭连接：{}", ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 通道断开回调
     * 
     * @param ctx Channel 上下文
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        
        if (playerId != null) {
            playerService.unregisterPlayer(playerId);
            logger.info("👋 玩家 {} 断开连接", playerId);
        }
        
        logger.info("🔌 连接关闭：{}", ctx.channel().remoteAddress());
    }

    /**
     * 异常处理回调
     * 
     * @param ctx Channel 上下文
     * @param cause 异常对象
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("❌ Channel 异常：{} - {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }

    /**
     * 发送消息到客户端
     * 
     * @param ctx Channel 上下文
     * @param message 要发送的消息
     */
    private void sendToClient(ChannelHandlerContext ctx, PlayerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            TextWebSocketFrame frame = new TextWebSocketFrame(json);
            ctx.writeAndFlush(frame);
            logger.debug("📤 发送消息：{}", message.getType());
        } catch (Exception e) {
            logger.error("❌ 发送消息失败：{}", e.getMessage());
        }
    }
}
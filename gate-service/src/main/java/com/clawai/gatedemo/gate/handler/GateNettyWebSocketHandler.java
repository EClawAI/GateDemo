package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.gate.auth.TokenValidator;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.resilience.CircuitBreaker;
import com.clawai.gatedemo.gate.resilience.RateLimiter;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Netty WebSocket 业务处理器
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
@ChannelHandler.Sharable
public class GateNettyWebSocketHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(GateNettyWebSocketHandler.class);
    private static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("authenticated");

    private final PlayerService playerService;
    private final ObjectMapper objectMapper;
    private final TokenValidator tokenValidator;
    private final RateLimiter perPlayerRateLimiter;
    private final RateLimiter globalRateLimiter;
    private final CircuitBreaker grpcCircuitBreaker;

    public GateNettyWebSocketHandler(PlayerService playerService, ObjectMapper objectMapper,
                                     TokenValidator tokenValidator,
                                     @org.springframework.beans.factory.annotation.Qualifier("perPlayerRateLimiter") RateLimiter perPlayerRateLimiter,
                                     @org.springframework.beans.factory.annotation.Qualifier("globalRateLimiter") RateLimiter globalRateLimiter,
                                     CircuitBreaker grpcCircuitBreaker) {
        this.playerService = playerService;
        this.objectMapper = objectMapper;
        this.tokenValidator = tokenValidator;
        this.perPlayerRateLimiter = perPlayerRateLimiter;
        this.globalRateLimiter = globalRateLimiter;
        this.grpcCircuitBreaker = grpcCircuitBreaker;
    }

    /**
     * 通道激活回调
     * 
     * 当客户端与服务端建立 TCP 连接后调用
     * 此时 WebSocket 握手还未完成
     * 
     * @param ctx Channel 上下文，包含 Channel、Pipeline 等信息
     * @throws Exception 异常
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("📡 新连接建立：{}", ctx.channel().remoteAddress());
        // 不需要调用 super.channelActive(ctx)，因为父类实现为空
    }

    /**
     * 通道读取回调
     * 
     * 当接收到客户端发送的消息时调用
     * 只处理 TextWebSocketFrame 类型的消息（文本消息）
     * 
     * @param ctx Channel 上下文
     * @param frame WebSocket 文本帧
     * @throws Exception 异常
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        logger.debug("收到消息：{}", text);

        PlayerMessage message = objectMapper.readValue(text, PlayerMessage.class);
        String type = message.getType();

        // Global rate limit check
        if (!globalRateLimiter.tryAcquire("global")) {
            sendError(ctx, "RATE_LIMITED", "Server rate limit exceeded");
            return;
        }

        Boolean authenticated = ctx.channel().attr(AUTHENTICATED).get();

        if (!Boolean.TRUE.equals(authenticated)) {
            if ("auth".equals(type)) {
                handleAuth(ctx, message);
            } else {
                logger.warn("未认证连接发送非认证消息，关闭连接：{}", ctx.channel().remoteAddress());
                ctx.close();
            }
            return;
        }

        // Per-player rate limit (heartbeat exempt)
        if (!"heartbeat".equals(type)) {
            Long rateLimitPlayerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
            String key = rateLimitPlayerId != null ? String.valueOf(rateLimitPlayerId) : ctx.channel().id().asShortText();
            if (!perPlayerRateLimiter.tryAcquire(key)) {
                sendError(ctx, "RATE_LIMITED", "Too many requests");
                return;
            }
        }

        if ("heartbeat".equals(type)) {
            handleHeartbeat(ctx, message);
        } else if ("game_msg".equals(type)) {
            handleGameMessage(ctx, message);
        } else {
            logger.warn("未知消息类型：{} from {}", type, ctx.channel().remoteAddress());
        }
    }

    /**
     * 认证流程：从消息 body 中解析 token，JWT 验签 + 黑名单检查
     * 客户端消息：{"type": "auth", "body": {"token": "eyJ..."}}
     */
    private void handleAuth(ChannelHandlerContext ctx, PlayerMessage message) {
        String token = null;
        if (message.getBody() != null) {
            Object t = message.getBody().get("token");
            if (t != null) token = t.toString();
        }

        Long playerId = tokenValidator.validate(token);
        if (playerId == null) {
            logger.warn("认证失败：无效 token from {}", ctx.channel().remoteAddress());
            PlayerMessage errResp = new PlayerMessage();
            errResp.setType("auth_fail");
            errResp.setTimestamp(System.currentTimeMillis());
            sendToClient(ctx, errResp);
            ctx.close();
            return;
        }

        if (playerService.hasPlayer(playerId)) {
            Channel oldChannel = playerService.getPlayerChannel(playerId);
            if (oldChannel != null && oldChannel.isActive()) {
                logger.warn("玩家 {} 已登录，关闭旧连接", playerId);
                oldChannel.close();
            }
            playerService.unregisterPlayer(playerId);
        }

        ctx.channel().attr(PlayerService.PLAYER_ID_KEY).set(playerId);
        ctx.channel().attr(AUTHENTICATED).set(true);

        playerService.registerPlayer(playerId, ctx.channel());
        logger.info("玩家 {} 认证成功 (JWT)", playerId);

        PlayerMessage response = new PlayerMessage();
        response.setType("auth_ack");
        response.setPlayerId(playerId);
        response.setTimestamp(System.currentTimeMillis());
        sendToClient(ctx, response);
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, PlayerMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId != null) {
            playerService.renewHeartbeat(playerId);
            PlayerMessage response = new PlayerMessage();
            response.setType("heartbeat_ack");
            response.setPlayerId(playerId);
            response.setTimestamp(System.currentTimeMillis());
            sendToClient(ctx, response);
            logger.debug("玩家 {} 心跳续期", playerId);
        }
    }

    private void handleGameMessage(ChannelHandlerContext ctx, PlayerMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        Integer gameId = message.getGameId();

        if (playerId == null || gameId == null) {
            logger.warn("Game message missing playerId or gameId: {}", message);
            return;
        }

        if (!grpcCircuitBreaker.allowRequest()) {
            sendError(ctx, "GAME_UNAVAILABLE", "Game service temporarily unavailable");
            return;
        }

        try {
            playerService.forwardToGame(playerId, gameId, message);
            grpcCircuitBreaker.recordSuccess();
            logger.debug("Game message forwarded: playerId={}, gameId={}, msgType={}", playerId, gameId, message.getMsgType());
        } catch (Exception e) {
            grpcCircuitBreaker.recordFailure();
            sendError(ctx, "GAME_UNAVAILABLE", "Failed to forward to game service");
            logger.error("Failed to forward game message: {}", e.getMessage());
        }
    }

    /**
     * 通道空闲回调
     * 
     * 当 Channel 在指定时间内没有读写操作时调用
     * 用于心跳检测和超时断开
     * 
     * @param ctx Channel 上下文
     * @param evt 事件对象
     * @throws Exception 异常
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent) {
            IdleStateEvent event = (IdleStateEvent) evt;
            
            // 判断是哪种空闲事件
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
     * 当客户端断开连接时调用
     * 清理玩家注册信息
     * 
     * @param ctx Channel 上下文
     * @throws Exception 异常
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 获取绑定的 player_id
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        
        if (playerId != null) {
            // 注销玩家
            playerService.unregisterPlayer(playerId);
            logger.info("👋 玩家 {} 断开连接", playerId);
        }
        
        logger.info("🔌 连接关闭：{}", ctx.channel().remoteAddress());
    }

    /**
     * 异常处理回调
     * 
     * 当 Channel 处理过程中发生异常时调用
     * 记录错误日志并关闭连接
     * 
     * @param ctx Channel 上下文
     * @param cause 异常对象
     * @throws Exception 异常
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("❌ Channel 异常：{} - {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();  // 关闭连接
    }

    /**
     * 发送消息到客户端
     * 
     * 将 PlayerMessage 对象转换为 JSON 字符串，
     * 然后封装为 TextWebSocketFrame 发送
     * 
     * @param ctx Channel 上下文
     * @param message 要发送的消息
     */
    private void sendToClient(ChannelHandlerContext ctx, PlayerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send message: {}", e.getMessage());
        }
    }

    private void sendError(ChannelHandlerContext ctx, String code, String message) {
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "error", "code", code, "message", message));
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send error: {}", e.getMessage());
        }
    }
}
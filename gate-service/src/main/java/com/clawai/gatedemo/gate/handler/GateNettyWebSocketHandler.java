package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.gate.auth.TokenValidator;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.resilience.CircuitBreaker;
import com.clawai.gatedemo.gate.resilience.ConnectionLimiter;
import com.clawai.gatedemo.gate.resilience.RateLimiter;
import com.clawai.gatedemo.gate.security.MessageValidator;
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

    /** Channel 属性：WebSocket 是否已完成 JWT 认证。 */
    private static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("authenticated");
    /** Channel 属性：是否在 {@link #channelActive} 中成功占用全局限流名额，断开时需释放。 */
    private static final AttributeKey<Boolean> CONNECTION_ACQUIRED = AttributeKey.valueOf("connectionAcquired");

    private final PlayerService playerService;
    private final ObjectMapper objectMapper;
    private final TokenValidator tokenValidator;
    private final MessageValidator messageValidator;
    private final RateLimiter perPlayerRateLimiter;
    private final RateLimiter globalRateLimiter;
    private final CircuitBreaker grpcCircuitBreaker;
    private final ConnectionLimiter connectionLimiter;

    /**
     * @param perPlayerRateLimiter 按玩家（或 channel）限流，心跳消息豁免
     * @param globalRateLimiter    全站共享桶，在认证前即生效
     * @param grpcCircuitBreaker   Game 转发失败累积时快速拒绝
     * @param connectionLimiter    最大并发 WebSocket/TCP 连接数
     */
    public GateNettyWebSocketHandler(PlayerService playerService, ObjectMapper objectMapper,
                                     TokenValidator tokenValidator,
                                     MessageValidator messageValidator,
                                     @org.springframework.beans.factory.annotation.Qualifier("perPlayerRateLimiter") RateLimiter perPlayerRateLimiter,
                                     @org.springframework.beans.factory.annotation.Qualifier("globalRateLimiter") RateLimiter globalRateLimiter,
                                     CircuitBreaker grpcCircuitBreaker,
                                     ConnectionLimiter connectionLimiter) {
        this.playerService = playerService;
        this.objectMapper = objectMapper;
        this.tokenValidator = tokenValidator;
        this.messageValidator = messageValidator;
        this.perPlayerRateLimiter = perPlayerRateLimiter;
        this.globalRateLimiter = globalRateLimiter;
        this.grpcCircuitBreaker = grpcCircuitBreaker;
        this.connectionLimiter = connectionLimiter;
    }

    /**
     * 新 TCP 连接建立时尝试占用全站连接配额；超限则 {@link Channel#close()}，否则标记 {@link #CONNECTION_ACQUIRED}。
     *
     * @param ctx Netty 上下文
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (!connectionLimiter.tryAcquire()) {
            logger.warn("连接数已达上限 {}，拒绝新连接：{}", connectionLimiter.getMaxConnections(), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.channel().attr(CONNECTION_ACQUIRED).set(true);
        logger.info("📡 新连接建立：{} (当前连接数: {})", ctx.channel().remoteAddress(), connectionLimiter.getActiveCount());
    }

    /**
     * 解析 JSON 为 {@link PlayerMessage}，先做结构与全局限流；未认证仅允许 {@code auth}，已认证则处理心跳/游戏消息并做玩家级限流。
     * 可能向客户端写错误帧或关闭连接（非法类型、鉴权失败、顶号关旧连接等）。
     *
     * @param ctx   当前连接
     * @param frame 文本帧负载
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        logger.debug("收到消息：{}", text);

        PlayerMessage message = objectMapper.readValue(text, PlayerMessage.class);
        String validationError = messageValidator.validate(message);
        if (validationError != null) {
            sendError(ctx, "INVALID_MESSAGE", validationError);
            return;
        }

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
     * 从 body 取 token，校验后注册玩家、顶掉同账号旧连接，并回复 {@code auth_ack}；失败则 {@code auth_fail} 并关连接。
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

    /**
     * 刷新玩家在 {@link PlayerService} 中的心跳时间并回复 {@code heartbeat_ack}（无玩家绑定则忽略）。
     */
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

    /**
     * 经熔断检查后转发至 Game gRPC；成功/失败分别计入熔断并可能向客户端返回 {@code GAME_UNAVAILABLE}。
     */
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
     * 读空闲（由 Pipeline 空闲检测配置）时关闭连接，用于清理长静默会话。
     *
     * @param ctx 当前连接
     * @param evt 非 {@link IdleStateEvent} 时委托父类
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
     * 释放连接配额、注销已绑定玩家并打日志。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        // 释放连接计数（若曾获取过）
        if (Boolean.TRUE.equals(ctx.channel().attr(CONNECTION_ACQUIRED).get())) {
            connectionLimiter.release();
        }

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
     * 记录异常并关闭 Channel，避免半开连接残留。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("❌ Channel 异常：{} - {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();  // 关闭连接
    }

    /**
     * 将 {@link PlayerMessage} 序列化为 JSON 文本帧写出；序列化失败仅打日志。
     */
    private void sendToClient(ChannelHandlerContext ctx, PlayerMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } catch (Exception e) {
            logger.error("Failed to send message: {}", e.getMessage());
        }
    }

    /**
     * 下发统一错误 JSON（type=error），供限流、熔断、校验失败等场景复用。
     */
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
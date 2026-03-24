package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.auth.TokenValidator;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.resilience.CircuitBreaker;
import com.clawai.gatedemo.gate.resilience.ConnectionLimiter;
import com.clawai.gatedemo.gate.resilience.RateLimiter;
import com.clawai.gatedemo.gate.route.GameServiceRouter;
import com.clawai.gatedemo.gate.route.ServiceRouter;
import com.clawai.gatedemo.gate.route.ServiceRouterManager;
import com.clawai.gatedemo.gate.security.MessageValidator;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ErrorResponse;
import com.clawai.gatedemo.proto.gate.HeartbeatAck;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Netty WebSocket 业务处理器（二进制协议版）。
 * <p>
 * 接收 {@link WrappedMessage}（由 {@link com.clawai.gatedemo.gate.ws.codec.WebSocketBinaryDecoder} 解码），
 * 按 {@link MessageRouteRegistry} 中注册的 messageId → targetService 路由：
 * <ul>
 *   <li>"gate" — 本地处理（认证、心跳）</li>
 *   <li>其他 — 委托 {@link ServiceRouterManager} 转发到对应后端服务</li>
 * </ul>
 */
@Component
@ChannelHandler.Sharable
public class GateNettyWebSocketHandler extends SimpleChannelInboundHandler<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(GateNettyWebSocketHandler.class);

    private static final AttributeKey<Boolean> AUTHENTICATED = AttributeKey.valueOf("authenticated");
    private static final AttributeKey<Boolean> CONNECTION_ACQUIRED = AttributeKey.valueOf("connectionAcquired");

    private static final short MSG_ID_AUTH = 0x1001;
    private static final short MSG_ID_HEARTBEAT = 0x2001;
    private static final short MSG_ID_HEARTBEAT_ACK = 0x2002;

    private final PlayerService playerService;
    private final TokenValidator tokenValidator;
    private final MessageValidator messageValidator;
    private final RateLimiter perPlayerRateLimiter;
    private final RateLimiter globalRateLimiter;
    private final CircuitBreaker grpcCircuitBreaker;
    private final ConnectionLimiter connectionLimiter;
    private final ServiceRouterManager routerManager;

    public GateNettyWebSocketHandler(PlayerService playerService,
                                     TokenValidator tokenValidator,
                                     MessageValidator messageValidator,
                                     @org.springframework.beans.factory.annotation.Qualifier("perPlayerRateLimiter") RateLimiter perPlayerRateLimiter,
                                     @org.springframework.beans.factory.annotation.Qualifier("globalRateLimiter") RateLimiter globalRateLimiter,
                                     CircuitBreaker grpcCircuitBreaker,
                                     ConnectionLimiter connectionLimiter,
                                     ServiceRouterManager routerManager) {
        this.playerService = playerService;
        this.tokenValidator = tokenValidator;
        this.messageValidator = messageValidator;
        this.perPlayerRateLimiter = perPlayerRateLimiter;
        this.globalRateLimiter = globalRateLimiter;
        this.grpcCircuitBreaker = grpcCircuitBreaker;
        this.connectionLimiter = connectionLimiter;
        this.routerManager = routerManager;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        if (!connectionLimiter.tryAcquire()) {
            logger.warn("连接数已达上限 {}，拒绝新连接：{}", connectionLimiter.getMaxConnections(), ctx.channel().remoteAddress());
            ctx.close();
            return;
        }
        ctx.channel().attr(CONNECTION_ACQUIRED).set(true);
        logger.info("新连接建立：{} (当前连接数: {})", ctx.channel().remoteAddress(), connectionLimiter.getActiveCount());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
        if (message == null || message.getHeader() == null) {
            return;
        }

        short messageId = message.getHeader().getMessageId();

        String validationError = messageValidator.validate(message);
        if (validationError != null) {
            sendError(ctx, messageId, "INVALID_MESSAGE", validationError);
            return;
        }

        if (!globalRateLimiter.tryAcquire("global")) {
            sendError(ctx, messageId, "RATE_LIMITED", "Server rate limit exceeded");
            return;
        }

        Boolean authenticated = ctx.channel().attr(AUTHENTICATED).get();

        if (!Boolean.TRUE.equals(authenticated)) {
            if (messageId == MSG_ID_AUTH) {
                handleAuth(ctx, message);
            } else {
                logger.warn("未认证连接发送非认证消息 msgId=0x{}, 关闭连接：{}",
                        Integer.toHexString(messageId & 0xFFFF), ctx.channel().remoteAddress());
                ctx.close();
            }
            return;
        }

        // 已认证：除心跳外做玩家级限流
        if (messageId != MSG_ID_HEARTBEAT) {
            Long rateLimitPlayerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
            String key = rateLimitPlayerId != null ? String.valueOf(rateLimitPlayerId) : ctx.channel().id().asShortText();
            if (!perPlayerRateLimiter.tryAcquire(key)) {
                sendError(ctx, messageId, "RATE_LIMITED", "Too many requests");
                return;
            }
        }

        // 按路由表决策
        String targetService = MessageRouteRegistry.getTargetService(messageId);

        if ("gate".equals(targetService)) {
            handleGateMessage(ctx, messageId, message);
        } else if (targetService != null) {
            handleServiceForward(ctx, messageId, targetService, message);
        } else {
            logger.warn("未注册的 messageId=0x{} from {}",
                    Integer.toHexString(messageId & 0xFFFF), ctx.channel().remoteAddress());
        }
    }

    private void handleGateMessage(ChannelHandlerContext ctx, short messageId, WrappedMessage message) {
        if (messageId == MSG_ID_HEARTBEAT) {
            handleHeartbeat(ctx, message);
        } else {
            logger.warn("Gate 本地不支持处理 messageId=0x{}", Integer.toHexString(messageId & 0xFFFF));
        }
    }

    private void handleServiceForward(ChannelHandlerContext ctx, short messageId,
                                       String targetService, WrappedMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId == null) {
            logger.warn("玩家未绑定，无法转发 messageId=0x{}", Integer.toHexString(messageId & 0xFFFF));
            return;
        }

        if (!grpcCircuitBreaker.allowRequest()) {
            sendError(ctx, messageId, "SERVICE_UNAVAILABLE", targetService + " service temporarily unavailable");
            return;
        }

        ServiceRouter router = routerManager.getRouter(targetService);
        if (router == null) {
            logger.error("未找到服务路由器: service={}", targetService);
            sendError(ctx, messageId, "SERVICE_UNAVAILABLE", "No router for service: " + targetService);
            return;
        }

        try {
            router.forward(ctx, message, playerId);
            grpcCircuitBreaker.recordSuccess();
        } catch (Exception e) {
            grpcCircuitBreaker.recordFailure();
            sendError(ctx, messageId, "SERVICE_UNAVAILABLE", "Failed to forward to " + targetService);
            logger.error("消息转发失败: service={}, error={}", targetService, e.getMessage());
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, WrappedMessage message) {
        try {
            byte[] bodyBytes = message.getBody() != null ? message.getBody().toBytes() : new byte[0];
            AuthRequest authReq = AuthRequest.parseFrom(bodyBytes);

            String token = authReq.getToken();
            int gameId = authReq.getGameId();

            Long playerId = tokenValidator.validate(token);
            if (playerId == null) {
                logger.warn("认证失败：无效 token from {}", ctx.channel().remoteAddress());
                AuthResponse resp = AuthResponse.newBuilder()
                        .setSuccess(false).setMessage("Invalid token").build();
                sendResponse(ctx, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());
                ctx.close();
                return;
            }

            // 顶号
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
            ctx.channel().attr(GameServiceRouter.GAME_ID_KEY).set(gameId);

            playerService.registerPlayer(playerId, ctx.channel());
            logger.info("玩家 {} 认证成功, gameId={}", playerId, gameId);

            AuthResponse resp = AuthResponse.newBuilder()
                    .setSuccess(true).setPlayerId(playerId).setMessage("OK").build();
            sendResponse(ctx, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());

        } catch (Exception e) {
            logger.error("认证消息解析失败: {}", e.getMessage());
            ctx.close();
        }
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, WrappedMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId != null) {
            playerService.renewHeartbeat(playerId);

            HeartbeatAck ack = HeartbeatAck.newBuilder()
                    .setServerTime(System.currentTimeMillis()).build();
            sendResponse(ctx, MSG_ID_HEARTBEAT_ACK, MessageHeader.MODE_RESPONSE, ack.toByteArray());

            logger.debug("玩家 {} 心跳续期", playerId);
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event) {
            if (event.state() == IdleState.READER_IDLE) {
                logger.warn("玩家心跳超时，关闭连接：{}", ctx.channel().remoteAddress());
                ctx.close();
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (Boolean.TRUE.equals(ctx.channel().attr(CONNECTION_ACQUIRED).get())) {
            connectionLimiter.release();
        }

        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId != null) {
            playerService.unregisterPlayer(playerId);
            logger.info("玩家 {} 断开连接", playerId);
        }

        logger.info("连接关闭：{}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Channel 异常：{} - {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }

    /** 发送带 protobuf body 的响应消息。 */
    private void sendResponse(ChannelHandlerContext ctx, short messageId, short mode, byte[] body) {
        WrappedMessage reply = new WrappedMessage();
        reply.getHeader().setMessageId(messageId);
        reply.getHeader().setMode(mode);
        reply.setBody(new RawMessageBody(body));
        ctx.writeAndFlush(reply);
    }

    /** 发送 ErrorResponse 消息。 */
    private void sendError(ChannelHandlerContext ctx, short messageId, String code, String message) {
        ErrorResponse err = ErrorResponse.newBuilder().setCode(code).setMessage(message).build();
        sendResponse(ctx, messageId, MessageHeader.MODE_RESPONSE, err.toByteArray());
    }
}

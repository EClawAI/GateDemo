package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.auth.TokenValidator;
import com.clawai.gatedemo.gate.flow.FlowResumeOutcome;
import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.resilience.CircuitBreaker;
import com.clawai.gatedemo.gate.resilience.ConnectionLimiter;
import com.clawai.gatedemo.gate.resilience.RateLimiter;
import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.route.GameServiceRouter;
import com.clawai.gatedemo.gate.route.ServiceRouter;
import com.clawai.gatedemo.gate.route.ServiceRouterManager;
import com.clawai.gatedemo.gate.security.MessageValidator;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import com.clawai.gatedemo.proto.gate.ErrorResponse;
import com.clawai.gatedemo.proto.gate.HeartbeatAck;
import com.clawai.gatedemo.proto.gate.ResumeStatus;
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

    private static final int MSG_ID_AUTH = MessageRouteRegistry.getIdByName("AuthRequest");
    private static final int MSG_ID_HEARTBEAT = MessageRouteRegistry.getIdByName("ClientHeartbeat");
    private static final int MSG_ID_HEARTBEAT_ACK = MessageRouteRegistry.getIdByName("HeartbeatAck");

    private final PlayerService playerService;
    private final TokenValidator tokenValidator;
    private final MessageValidator messageValidator;
    private final RateLimiter perPlayerRateLimiter;
    private final RateLimiter globalRateLimiter;
    private final CircuitBreaker grpcCircuitBreaker;
    private final ConnectionLimiter connectionLimiter;
    private final ServiceRouterManager routerManager;
    private final GateConfig gateConfig;
    private final GameGrpcClientPool gameGrpcClientPool;
    private final FlowSessionManager flowSessionManager;
    /** B3：NEW 首登时 drain 兜底 stream + 触发 RELOGIN 阈值；setter 注入，避免循环依赖。 */
    private com.clawai.gatedemo.gate.service.OfflineMessageService offlineMessageService;

    public GateNettyWebSocketHandler(PlayerService playerService,
                                     TokenValidator tokenValidator,
                                     MessageValidator messageValidator,
                                     @org.springframework.beans.factory.annotation.Qualifier("perPlayerRateLimiter") RateLimiter perPlayerRateLimiter,
                                     @org.springframework.beans.factory.annotation.Qualifier("globalRateLimiter") RateLimiter globalRateLimiter,
                                     CircuitBreaker grpcCircuitBreaker,
                                     ConnectionLimiter connectionLimiter,
                                     ServiceRouterManager routerManager,
                                     GateConfig gateConfig,
                                     GameGrpcClientPool gameGrpcClientPool,
                                     FlowSessionManager flowSessionManager) {
        this.playerService = playerService;
        this.tokenValidator = tokenValidator;
        this.messageValidator = messageValidator;
        this.perPlayerRateLimiter = perPlayerRateLimiter;
        this.globalRateLimiter = globalRateLimiter;
        this.grpcCircuitBreaker = grpcCircuitBreaker;
        this.connectionLimiter = connectionLimiter;
        this.routerManager = routerManager;
        this.gateConfig = gateConfig;
        this.gameGrpcClientPool = gameGrpcClientPool;
        this.flowSessionManager = flowSessionManager;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOfflineMessageService(
            @org.springframework.context.annotation.Lazy
            com.clawai.gatedemo.gate.service.OfflineMessageService offlineMessageService) {
        this.offlineMessageService = offlineMessageService;
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

        int messageId = message.getHeader().getMessageId();

        MessageHeader reqHeader = message.getHeader();

        String validationError = messageValidator.validate(message);
        if (validationError != null) {
            sendError(ctx, reqHeader, messageId, "INVALID_MESSAGE", validationError);
            return;
        }

        if (!globalRateLimiter.tryAcquire("global")) {
            sendError(ctx, reqHeader, messageId, "RATE_LIMITED", "Server rate limit exceeded");
            return;
        }

        Boolean authenticated = ctx.channel().attr(AUTHENTICATED).get();

        if (!Boolean.TRUE.equals(authenticated)) {
            if (messageId == MSG_ID_AUTH) {
                handleAuth(ctx, message);
            } else {
                logger.warn("未认证连接发送非认证消息 msgId={}, 关闭连接：{}",
                        messageId, ctx.channel().remoteAddress());
                ctx.close();
            }
            return;
        }

        // 已认证：除心跳外做玩家级限流
        if (messageId != MSG_ID_HEARTBEAT) {
            Long rateLimitPlayerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
            String key = rateLimitPlayerId != null ? String.valueOf(rateLimitPlayerId) : ctx.channel().id().asShortText();
            if (!perPlayerRateLimiter.tryAcquire(key)) {
                sendError(ctx, reqHeader, messageId, "RATE_LIMITED", "Too many requests");
                return;
            }
        }

        // 按路由表决策
        String targetService = MessageRouteRegistry.getTargetService(messageId);

        if ("gate".equals(targetService)) {
            handleGateMessage(ctx, messageId, reqHeader, message);
        } else if (targetService != null) {
            handleServiceForward(ctx, messageId, reqHeader, targetService, message);
        } else {
            logger.warn("未注册的 messageId={} from {}",
                    messageId, ctx.channel().remoteAddress());
        }
    }

    private void handleGateMessage(ChannelHandlerContext ctx, int messageId,
                                    MessageHeader reqHeader, WrappedMessage message) {
        if (messageId == MSG_ID_HEARTBEAT) {
            handleHeartbeat(ctx, reqHeader, message);
        } else {
            logger.warn("Gate 本地不支持处理 messageId={}", messageId);
        }
    }

    private void handleServiceForward(ChannelHandlerContext ctx, int messageId,
                                       MessageHeader reqHeader,
                                       String targetService, WrappedMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId == null) {
            logger.warn("玩家未绑定，无法转发 messageId={}", messageId);
            return;
        }

        if (!grpcCircuitBreaker.allowRequest()) {
            sendError(ctx, reqHeader, messageId, "SERVICE_UNAVAILABLE", targetService + " service temporarily unavailable");
            return;
        }

        ServiceRouter router = routerManager.getRouter(targetService);
        if (router == null) {
            logger.error("未找到服务路由器: service={}", targetService);
            sendError(ctx, reqHeader, messageId, "SERVICE_UNAVAILABLE", "No router for service: " + targetService);
            return;
        }

        try {
            router.forward(ctx, message, playerId);
            grpcCircuitBreaker.recordSuccess();
        } catch (Exception e) {
            grpcCircuitBreaker.recordFailure();
            sendError(ctx, reqHeader, messageId, "SERVICE_UNAVAILABLE", "Failed to forward to " + targetService);
            logger.error("消息转发失败: service={}, error={}", targetService, e.getMessage());
        }
    }

    private void handleAuth(ChannelHandlerContext ctx, WrappedMessage message) {
        MessageHeader reqHeader = message.getHeader();
        try {
            byte[] bodyBytes = message.getBody() != null ? message.getBody().toBytes() : new byte[0];
            AuthRequest authReq = AuthRequest.parseFrom(bodyBytes);

            String token = authReq.getToken();
            int gameId = authReq.getGameId();
            String clientFlowId = authReq.getFlowId();
            long lastClientRecvSeq = authReq.getLastClientRecvSeq();
            int clientFeatures = authReq.getClientFeatures();

            if (gameId <= 0) {
                logger.warn("认证失败：无效 gameId from {}", ctx.channel().remoteAddress());
                AuthResponse resp = AuthResponse.newBuilder()
                        .setSuccess(false).setMessage("Invalid game id").build();
                sendResponse(ctx, reqHeader, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());
                ctx.close();
                return;
            }

            Long playerId = tokenValidator.validate(token);
            if (playerId == null) {
                logger.warn("认证失败：无效 token from {}", ctx.channel().remoteAddress());
                AuthResponse resp = AuthResponse.newBuilder()
                        .setSuccess(false).setMessage("Invalid token").build();
                sendResponse(ctx, reqHeader, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());
                ctx.close();
                return;
            }

            if (gateConfig.getGrpcPool().isLazyConnect()) {
                gameGrpcClientPool.ensureConnectedAsync(gameId).thenAcceptAsync(ok -> {
                    if (Boolean.FALSE.equals(ok)) {
                        logger.warn("认证失败：无法连接 game {} from {}", gameId, ctx.channel().remoteAddress());
                        AuthResponse resp = AuthResponse.newBuilder()
                                .setSuccess(false).setMessage("Game unavailable").build();
                        sendResponse(ctx, reqHeader, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());
                        ctx.close();
                        return;
                    }
                    gameGrpcClientPool.acquireRef(gameId);
                    finishAuthAndRespond(ctx, reqHeader, playerId, gameId, clientFlowId, lastClientRecvSeq, clientFeatures);
                }, ctx.channel().eventLoop());
                return;
            }

            gameGrpcClientPool.acquireRef(gameId);
            finishAuthAndRespond(ctx, reqHeader, playerId, gameId, clientFlowId, lastClientRecvSeq, clientFeatures);

        } catch (Exception e) {
            logger.error("认证消息解析失败: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * AUTH 完成路径：按 {@code clientFlowId} 是否为空分叉 NEW / RESUME；RESUME 失败自动降级为 NEW
     * 并在响应中保留拒绝原因（{@code resume_status}）。B1 起：透传 client_features，回写 server_features。
     */
    private void finishAuthAndRespond(ChannelHandlerContext ctx, MessageHeader reqHeader,
                                      Long playerId, int gameId,
                                      String clientFlowId, long lastClientRecvSeq,
                                      int clientFeatures) {
        FlowSession session;
        ResumeStatus statusForResp;
        FlowResumeOutcome rejectReason = null;

        if (clientFlowId == null || clientFlowId.isBlank()) {
            session = flowSessionManager.newFlow(playerId, gameId, ctx.channel(), clientFeatures);
            statusForResp = ResumeStatus.NEW;
        } else {
            FlowSessionManager.ResumeResult result =
                    flowSessionManager.resume(clientFlowId, playerId, lastClientRecvSeq, ctx.channel(), clientFeatures);
            if (result.isResumed()) {
                session = result.session();
                statusForResp = ResumeStatus.RESUMED;
            } else {
                rejectReason = result.outcome();
                session = flowSessionManager.newFlowAfterReject(playerId, gameId, ctx.channel(), rejectReason, clientFeatures);
                statusForResp = toProtoStatus(rejectReason);
            }
        }

        ctx.channel().attr(PlayerService.PLAYER_ID_KEY).set(playerId);
        ctx.channel().attr(AUTHENTICATED).set(true);
        ctx.channel().attr(GameServiceRouter.GAME_ID_KEY).set(gameId);
        playerService.registerPlayer(playerId, ctx.channel());

        int negotiatedFeatures = session.getFeatures();
        if (statusForResp == ResumeStatus.RESUMED) {
            logger.info("玩家 {} RESUME 成功, flowId={}, gameId={}, features=0x{}",
                    playerId, session.getFlowId(), gameId, Integer.toHexString(negotiatedFeatures));
        } else if (rejectReason != null) {
            logger.info("玩家 {} RESUME 失败降级 NEW（{}）, newFlowId={}, gameId={}, features=0x{}",
                    playerId, rejectReason, session.getFlowId(), gameId, Integer.toHexString(negotiatedFeatures));
        } else {
            logger.info("玩家 {} NEW 认证成功, flowId={}, gameId={}, features=0x{}",
                    playerId, session.getFlowId(), gameId, Integer.toHexString(negotiatedFeatures));
        }

        AuthResponse resp = AuthResponse.newBuilder()
                .setSuccess(true)
                .setPlayerId(playerId)
                .setMessage("OK")
                .setFlowId(session.getFlowId())
                .setResumeStatus(statusForResp)
                .setServerFeatures(negotiatedFeatures)
                .build();
        sendResponse(ctx, reqHeader, MSG_ID_AUTH, MessageHeader.MODE_RESPONSE, resp.toByteArray());

        // B3：NEW 首登时 drain 兜底 stream + 检查 RELOGIN 阈值（RESUMED 路径由 manager 在 resume 内合流）
        if (offlineMessageService != null && statusForResp != ResumeStatus.RESUMED) {
            try {
                offlineMessageService.onPlayerOnline(playerId, ctx.channel());
            } catch (Throwable t) {
                logger.warn("OfflineMessageService.onPlayerOnline failed playerId={}: {}", playerId, t.getMessage());
            }
        }
    }

    private static ResumeStatus toProtoStatus(FlowResumeOutcome outcome) {
        return switch (outcome) {
            case RESUMED -> ResumeStatus.RESUMED;
            case REJECTED_EXPIRED -> ResumeStatus.REJECTED_EXPIRED;
            case REJECTED_MISMATCH -> ResumeStatus.REJECTED_MISMATCH;
            case REJECTED_OWNER_OTHER -> ResumeStatus.REJECTED_OWNER_OTHER;
            case NEW -> ResumeStatus.NEW;
        };
    }

    private void handleHeartbeat(ChannelHandlerContext ctx, MessageHeader reqHeader,
                                  WrappedMessage message) {
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId == null) return;

        playerService.renewHeartbeat(playerId);

        // B1：客户端心跳里捎带 last_client_recv_seq，用于裁剪 DownstreamBuffer / 续写锚点。
        try {
            byte[] body = message.getBody() != null ? message.getBody().toBytes() : null;
            if (body != null && body.length > 0) {
                ClientHeartbeat hb = ClientHeartbeat.parseFrom(body);
                long ack = hb.getLastClientRecvSeq();
                if (ack > 0) {
                    flowSessionManager.ackSeq(playerId, ack);
                }
            }
        } catch (Exception e) {
            logger.warn("ClientHeartbeat 解析失败 playerId={}: {}", playerId, e.getMessage());
        }

        HeartbeatAck ack = HeartbeatAck.newBuilder()
                .setServerTime(System.currentTimeMillis()).build();
        sendResponse(ctx, reqHeader, MSG_ID_HEARTBEAT_ACK, MessageHeader.MODE_RESPONSE, ack.toByteArray());

        logger.debug("玩家 {} 心跳续期", playerId);
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

        Integer gameId = ctx.channel().attr(GameServiceRouter.GAME_ID_KEY).get();
        if (gameId != null) {
            gameGrpcClientPool.releaseRef(gameId);
        }

        // FlowSession 处理：进入 DETACHED 等待 RESUME 窗口（design.md §4），
        // 而不是直接 destroy + 触发离线消息路径。超 TTL 由 FlowSessionManager 扫描清理。
        Long playerId = ctx.channel().attr(PlayerService.PLAYER_ID_KEY).get();
        if (playerId != null) {
            flowSessionManager.markDetached(ctx.channel());
            logger.info("玩家 {} Channel 关闭，等待 RESUME 或 TTL 销毁", playerId);
        }

        logger.info("连接关闭：{}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("Channel 异常：{} - {}", ctx.channel().remoteAddress(), cause.getMessage());
        ctx.close();
    }

    /** 发送带 protobuf body 的响应消息，回传请求的 sequence 和 requestId 供客户端匹配。 */
    private void sendResponse(ChannelHandlerContext ctx, MessageHeader reqHeader,
                              int messageId, short mode, byte[] body) {
        WrappedMessage reply = new WrappedMessage();
        reply.getHeader().setMessageId(messageId);
        reply.getHeader().setMode(mode);
        if (reqHeader != null) {
            reply.getHeader().setSequence(reqHeader.getSequence());
            reply.getHeader().setRequestId(reqHeader.getRequestId());
        }
        reply.setBody(new RawMessageBody(body));
        ctx.writeAndFlush(reply);
    }

    /** 发送 ErrorResponse 消息。 */
    private void sendError(ChannelHandlerContext ctx, MessageHeader reqHeader,
                           int messageId, String code, String message) {
        ErrorResponse err = ErrorResponse.newBuilder().setCode(code).setMessage(message).build();
        sendResponse(ctx, reqHeader, messageId, MessageHeader.MODE_RESPONSE, err.toByteArray());
    }
}

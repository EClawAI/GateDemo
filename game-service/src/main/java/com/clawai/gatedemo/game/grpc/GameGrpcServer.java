package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.handler.GameMessageSender;
import com.clawai.gatedemo.game.pekko.bridge.StreamIngressBehavior;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import com.clawai.gatedemo.grpc.*;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Game 侧 gRPC 服务端：监听端口、注册 {@link GameServiceGrpc} 实现与健康检查；支持 Unary、双向流业务消息与心跳流。
 * <p>
 * 启用 TLS 时通过 {@code grpc.tls.*} 加载证书与私钥；关闭时优雅 shutdown 并更新健康状态为 NOT_SERVING。
 * <p>
 * 双向流上行：{@link StreamIngressBehavior} 将帧路由至 {@link PlayerSessionRegistryBehavior}（见
 * {@code openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/design.md} 与阶段 3 PlayerSession）。
 * Unary {@code SendGameMessage} 仍同步分发，后续可与流统一桥接（TODO）。
 */
@Component
public class GameGrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GameGrpcServer.class);

    /** 逻辑游戏 ID（日志展示；与 {@code game.id} 字符串配置可能同源不同形态）。 */
    @Value("${game.id:1001}")
    private int gameId;

    /** gRPC 绑定端口。 */
    @Value("${grpc.port:9090}")
    private int grpcPort;

    /** 是否为服务端连接启用 TLS。 */
    @Value("${grpc.tls.enabled:false}")
    private boolean tlsEnabled;

    /** PEM 证书路径（TLS 开启时与 key 同时使用）。 */
    @Value("${grpc.tls.cert-path:}")
    private String tlsCertPath;

    /** PEM 私钥路径。 */
    @Value("${grpc.tls.key-path:}")
    private String tlsKeyPath;

    private io.grpc.Server server;
    /** gRPC 标准健康检查服务所用状态管理器。 */
    private HealthStatusManager healthManager;
    private final GameMessageDispatcher dispatcher;
    private final ActorSystem<SpawnProtocol.Command> actorSystem;
    private final ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry;
    private final AtomicLong streamIngressSeq = new AtomicLong();

    public GameGrpcServer(
            GameMessageDispatcher dispatcher,
            ActorSystem<SpawnProtocol.Command> actorSystem,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry) {
        this.dispatcher = dispatcher;
        this.actorSystem = actorSystem;
        this.playerSessionRegistry = playerSessionRegistry;
    }

    /**
     * 构建并启动 gRPC Server、注册业务服务与健康检查；可选加载 TLS；注册 JVM shutdown 以调用 {@link #stop()}。
     *
     * @throws IOException 绑定端口或 TLS 文件读取失败时抛出
     */
    @PostConstruct
    public void start() throws IOException {
        logger.info("=== 启动 Game gRPC 服务器 ===");
        logger.info("Game ID: {}", gameId);
        logger.info("监听端口：{}", grpcPort);

        healthManager = new HealthStatusManager();
        io.grpc.ServerBuilder<?> builder = io.grpc.ServerBuilder.forPort(grpcPort)
            .addService(new GameServiceImpl())
            .addService(healthManager.getHealthService());

        if (tlsEnabled && tlsCertPath != null && !tlsCertPath.isBlank()
                && tlsKeyPath != null && !tlsKeyPath.isBlank()) {
            builder.useTransportSecurity(new File(tlsCertPath), new File(tlsKeyPath));
            logger.info("gRPC TLS enabled: cert={}, key={}", tlsCertPath, tlsKeyPath);
        }

        server = builder.build().start();
        healthManager.setStatus("", ServingStatus.SERVING);
        healthManager.setStatus("game-service", ServingStatus.SERVING);

        logger.info("===========================================");
        logger.info("✅ Game gRPC 服务器启动成功！");
        logger.info("Game ID: {}", gameId);
        logger.info("监听地址：0.0.0.0:{}", grpcPort);
        logger.info("===========================================");

        // 添加关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止 gRPC 服务器...");
            GameGrpcServer.this.stop();
            logger.info("gRPC 服务器已关闭");
        }));
    }

    /**
     * 停止 gRPC 服务器
     */
    @PreDestroy
    public void stop() {
        if (healthManager != null) {
            healthManager.setStatus("", ServingStatus.NOT_SERVING);
        }
        if (server != null) {
            try {
                server.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                server.shutdownNow();
            }
        }
    }

    /**
     * gRPC 服务实现
     */
    private class GameServiceImpl extends GameServiceGrpc.GameServiceImplBase {

        /**
         * 处理游戏消息
         * 
         * @param request 游戏消息请求
         * @param responseObserver 响应观察者
         */
        @Override
        public void sendGameMessage(GameMessage request, StreamObserver<GameResponse> responseObserver) {
            try {
                logger.debug("收到 Unary 消息: gateId={}, playerId={}, msgId={}",
                    request.getGateId(), request.getPlayerId(), request.getMsgId());
                // TODO: optional future change — route Unary through the same stream-ingress actor pattern.

                dispatcher.dispatch(
                    request.getPlayerId(),
                    request.getMsgId(),
                    request.getSeq(),
                    request.getBody().toByteArray()
                );

                GameResponse response = GameResponse.newBuilder()
                    .setCode(0)
                    .setMessage("Success")
                    .setTimestamp(System.currentTimeMillis())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (Exception e) {
                logger.error("处理 Unary 消息失败: {}", e.getMessage());

                GameResponse response = GameResponse.newBuilder()
                    .setCode(1)
                    .setMessage(e.getMessage())
                    .setTimestamp(System.currentTimeMillis())
                    .build();

                responseObserver.onNext(response);
                responseObserver.onCompleted();
            }
        }

        /**
         * Stream双向流通信（新增）
         * 
         * 支持Gate和Game之间的双向实时消息传递
         * 
         * @param responseObserver 响应观察者
         * @return 请求观察者
         */
        @Override
        public StreamObserver<GameMessage> streamCommunication(StreamObserver<GameMessage> responseObserver) {
            logger.info("📡 Game Stream双向流通信已建立");

            GameMessageSender sender = new GameMessageSender(
                    responseObserver, "game-" + gameId);
            dispatcher.setSender(sender);

            long streamId = streamIngressSeq.incrementAndGet();
            String name = "stream-ingress-" + streamId;
            Props props = Props.empty().withMailboxFromConfig("pekko.actor.mailbox.stream-ingress-bounded");
            CompletionStage<ActorRef<StreamIngressBehavior.Command>> started = AskPattern.ask(
                    actorSystem,
                    replyTo -> new SpawnProtocol.Spawn<>(
                            StreamIngressBehavior.create(streamId, playerSessionRegistry),
                            name,
                            props,
                            replyTo),
                    Duration.ofSeconds(3),
                    actorSystem.scheduler());
            ActorRef<StreamIngressBehavior.Command> ingress = started.toCompletableFuture().join();

            return new GameStreamInboundObserver(ingress, responseObserver, dispatcher, logger);
        }

        /**
         * 处理心跳（双向流）
         * 
         * @param responseObserver 响应观察者
         * @return 请求观察者
         */
        @Override
        public StreamObserver<HeartbeatRequest> heartbeat(StreamObserver<HeartbeatResponse> responseObserver) {
            logger.info("💓 gRPC 心跳流已建立");

            return new StreamObserver<HeartbeatRequest>() {
                /** 回写 code=0 与当前服务端时间戳。 */
                @Override
                public void onNext(HeartbeatRequest request) {
                    logger.debug("💓 收到心跳：gateId={}, timestamp={}", request.getGateId(), request.getTimestamp());

                    // 发送心跳响应
                    HeartbeatResponse response = HeartbeatResponse.newBuilder()
                        .setCode(0)
                        .setServerTime(System.currentTimeMillis())
                        .build();

                    responseObserver.onNext(response);
                }

                /** 连接异常或取消时告警日志。 */
                @Override
                public void onError(Throwable t) {
                    // 客户端取消导致的错误是正常的，不打印error
                    logger.warn("⚠️ 心跳流断开：{} (客户端可能已断开)", t.getMessage());
                }

                /** 对端正常结束流。 */
                @Override
                public void onCompleted() {
                    logger.info("🔚 心跳流完成");
                    responseObserver.onCompleted();
                }
            };
        }
    }
}

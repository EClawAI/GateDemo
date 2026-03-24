package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.service.GameMessageHandler;
import com.clawai.gatedemo.game.service.OutgoingMessageSink;
import com.clawai.gatedemo.grpc.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Game 侧 gRPC 服务端：监听端口、注册 {@link GameServiceGrpc} 实现与健康检查；支持 Unary、双向流业务消息与心跳流。
 * <p>
 * 启用 TLS 时通过 {@code grpc.tls.*} 加载证书与私钥；关闭时优雅 shutdown 并更新健康状态为 NOT_SERVING。
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
    private final GameMessageHandler gameMessageHandler;
    private final ObjectMapper objectMapper;

    /**
     * @param gameMessageHandler 处理 Unary/流式游戏消息
     * @param objectMapper       下行 body 序列化
     */
    public GameGrpcServer(GameMessageHandler gameMessageHandler, ObjectMapper objectMapper) {
        this.gameMessageHandler = gameMessageHandler;
        this.objectMapper = objectMapper;
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
                logger.debug("📥 收到游戏消息：gateId={}, playerId={}, gameId={}, msgType={}",
                    request.getGateId(), request.getPlayerId(), request.getGameId(), request.getMsgType());

                // 1. 处理游戏消息
                gameMessageHandler.handleGameMessage(
                    request.getPlayerId(),
                    request.getGameId(),
                    request.getMsgType(),
                    request.getSeq(),
                    request.getBody()
                );

                // 2. 构建成功响应
                GameResponse response = GameResponse.newBuilder()
                    .setCode(0)
                    .setMessage("Success")
                    .setTimestamp(System.currentTimeMillis())
                    .build();

                // 3. 发送响应
                responseObserver.onNext(response);
                responseObserver.onCompleted();

            } catch (Exception e) {
                logger.error("❌ 处理游戏消息失败：{}", e.getMessage());

                // 构建错误响应
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

            return new StreamObserver<GameMessage>() {
                @Override
                public void onNext(GameMessage request) {
                    logger.debug("📥 收到Stream消息：gateId={}, playerId={}, msgType={}",
                        request.getGateId(), request.getPlayerId(), request.getMsgType());

                    try {
                        OutgoingMessageSink sink = (playerId, gameId, msgType, seq, body) -> {
                            try {
                                com.google.protobuf.ByteString bodyBytes = com.google.protobuf.ByteString
                                    .copyFromUtf8(objectMapper.writeValueAsString(body));
                                GameMessage out = GameMessage.newBuilder()
                                    .setGateId(request.getGateId())
                                    .setPlayerId(playerId)
                                    .setGameId(gameId)
                                    .setMsgType(msgType)
                                    .setSeq(seq)
                                    .setTimestamp(System.currentTimeMillis())
                                    .setBody(bodyBytes)
                                    .build();
                                responseObserver.onNext(out);
                            } catch (Exception ex) {
                                logger.error("❌ 发送流出消息失败：{}", ex.getMessage());
                            }
                        };
                        gameMessageHandler.handleGameMessage(
                            request.getPlayerId(),
                            request.getGameId(),
                            request.getMsgType(),
                            request.getSeq(),
                            request.getBody(),
                            sink
                        );
                    } catch (Exception e) {
                        logger.error("❌ 处理Stream消息失败：{}", e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    // 客户端取消导致的错误是正常的，不打印error
                    logger.warn("⚠️ Stream通信断开：{} (客户端可能已断开)", t.getMessage());
                }

                @Override
                public void onCompleted() {
                    logger.info("🔚 Gate端Stream通信完成");
                    responseObserver.onCompleted();
                }
            };
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

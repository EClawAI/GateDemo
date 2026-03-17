package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.service.GameMessageHandler;
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
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Game gRPC 服务端（Game 服务使用）
 * 
 * 功能说明：
 * 1. 监听 gRPC 端口，接受 Gate 服务的连接
 * 2. 处理游戏消息转发
 * 3. 维护双向流心跳
 * 4. 支持Stream双向流通信
 * 
 * 架构说明：
 * ┌─────────────────────────────────────────┐
 * │           Game gRPC Server              │
 * │                                         │
 * │  Port: 9090                             │
 * │    ↓ 接受 gRPC 连接                       │
 * │  GameServiceImpl                        │
 * │    ├─ SendGameMessage - 处理游戏消息    │
 * │    ├─ StreamCommunication - Stream双向流│
 * │    └─ Heartbeat - 处理心跳              │
 * └─────────────────────────────────────────┘
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@Component
public class GameGrpcServer {

    private static final Logger logger = LoggerFactory.getLogger(GameGrpcServer.class);

    /**
     * Game 服务 ID，从配置文件读取
     * 默认值：1001
     */
    @Value("${game.id:1001}")
    private int gameId;

    /**
     * gRPC 服务端口，从配置文件读取
     * 默认值：9090
     */
    @Value("${grpc.port:9090}")
    private int grpcPort;

    private io.grpc.Server server;
    private HealthStatusManager healthManager;
    private final GameMessageHandler gameMessageHandler;

    /**
     * 构造函数
     * 
     * @param gameMessageHandler 游戏消息处理器
     */
    public GameGrpcServer(GameMessageHandler gameMessageHandler) {
        this.gameMessageHandler = gameMessageHandler;
    }

    /**
     * 启动 gRPC 服务器
     */
    @PostConstruct
    public void start() throws IOException {
        logger.info("=== 启动 Game gRPC 服务器 ===");
        logger.info("Game ID: {}", gameId);
        logger.info("监听端口：{}", grpcPort);

        // 创建 gRPC 服务器（含 Health 服务）
        healthManager = new HealthStatusManager();
        server = io.grpc.ServerBuilder.forPort(grpcPort)
            .addService(new GameServiceImpl())
            .addService(healthManager.getHealthService())
            .build()
            .start();
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
                    // 收到Gate发送的消息
                    logger.debug("📥 收到Stream消息：gateId={}, playerId={}, msgType={}",
                        request.getGateId(), request.getPlayerId(), request.getMsgType());

                    try {
                        // 处理游戏消息
                        gameMessageHandler.handleGameMessage(
                            request.getPlayerId(),
                            request.getGameId(),
                            request.getMsgType(),
                            request.getSeq(),
                            request.getBody()
                        );

                        // 可以选择是否回复（这里不回复，由业务逻辑决定何时推送）
                        // 如果需要回复：
                        // GameMessage response = GameMessage.newBuilder()...build();
                        // responseObserver.onNext(response);

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

                @Override
                public void onError(Throwable t) {
                    // 客户端取消导致的错误是正常的，不打印error
                    logger.warn("⚠️ 心跳流断开：{} (客户端可能已断开)", t.getMessage());
                }

                @Override
                public void onCompleted() {
                    logger.info("🔚 心跳流完成");
                    responseObserver.onCompleted();
                }
            };
        }
    }
}

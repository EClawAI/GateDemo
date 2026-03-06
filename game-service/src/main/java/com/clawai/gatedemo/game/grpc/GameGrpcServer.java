package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.service.GameMessageHandler;
import com.clawai.gatedemo.grpc.*;
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
 * 
 * 架构说明：
 * ┌─────────────────────────────────────────┐
 * │           Game gRPC Server              │
 * │                                         │
 * │  Port: 9090                             │
 * │    ↓ 接受 gRPC 连接                       │
 * │  GameServiceImpl                        │
 * │    ├─ SendGameMessage - 处理游戏消息    │
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
     * gRPC 服务端口，从配置文件读取
     * 默认值：9090
     */
    @Value("${grpc.port:9090}")
    private int grpcPort;

    private io.grpc.Server server;
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
        logger.info("监听端口：{}", grpcPort);

        // 创建 gRPC 服务器
        server = io.grpc.ServerBuilder.forPort(grpcPort)
            .addService(new GameServiceImpl())
            .build()
            .start();

        logger.info("===========================================");
        logger.info("✅ Game gRPC 服务器启动成功！");
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
                    logger.error("❌ 心跳流错误：{}", t.getMessage());
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

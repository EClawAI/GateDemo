package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.grpc.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * Game gRPC 客户端（Gate 服务使用）
 * 
 * 功能说明：
 * 1. 与 Game 服务建立 gRPC 长连接
 * 2. 发送游戏消息到 Game 服务
 * 3. 维护心跳保持连接活跃
 * 
 * 与 HTTP 的区别：
 * - ✅ 长连接，避免每次握手开销
 * - ✅ HTTP/2 多路复用，并发更高
 * - ✅ Protobuf 序列化，比 JSON 更小更快
 * - ✅ 支持双向流心跳
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@Component
public class GameGrpcClient {

    private static final Logger logger = LoggerFactory.getLogger(GameGrpcClient.class);

    private final GateConfig gateConfig;
    private final ObjectMapper objectMapper;
    
    private ManagedChannel channel;
    private GameServiceGrpc.GameServiceBlockingStub blockingStub;
    private GameServiceGrpc.GameServiceStub asyncStub;
    private StreamObserver<HeartbeatRequest> heartbeatRequestObserver;
    
    /**
     * 构造函数
     * 
     * @param gateConfig Gate 配置
     * @param objectMapper JSON 序列化工具
     */
    public GameGrpcClient(GateConfig gateConfig, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 初始化 gRPC 客户端
     */
    @PostConstruct
    public void init() {
        String host = gateConfig.getGame().getHost();
        int port = gateConfig.getGame().getPort();
        
        logger.info("=== 初始化 gRPC 客户端 ===");
        logger.info("Game 服务地址：{}:{}", host, port);
        
        // 创建 gRPC 通道（长连接）
        this.channel = ManagedChannelBuilder
            .forAddress(host, port)
            .usePlaintext()  // 生产环境应使用 TLS
            .keepAliveTime(30, TimeUnit.SECONDS)  // 保持活跃时间
            .keepAliveTimeout(10, TimeUnit.SECONDS)  // 保持活跃超时
            .keepAliveWithoutCalls(true)  // 允许无请求时发送保持活跃
            .build();
        
        // 创建存根（Stub）
        this.blockingStub = GameServiceGrpc.newBlockingStub(channel);
        this.asyncStub = GameServiceGrpc.newStub(channel);
        
        // 启动心跳
        startHeartbeat();
        
        logger.info("✅ gRPC 客户端初始化完成");
    }
    
    /**
     * 发送游戏消息到 Game 服务
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param msgType 消息类型
     * @param seq 消息序列号
     * @param body 消息体（Java 对象）
     * @return 是否发送成功
     */
    public boolean sendGameMessage(Long playerId, Integer gameId, String msgType, int seq, Object body) {
        try {
            // 1. 将消息体转换为 JSON 字符串
            String bodyJson = objectMapper.writeValueAsString(body);
            
            // 2. 构建 gRPC 消息
            GameMessage message = GameMessage.newBuilder()
                .setGateId(gateConfig.getId())
                .setPlayerId(playerId)
                .setGameId(gameId)
                .setMsgType(msgType != null ? msgType : "unknown")
                .setSeq(seq)
                .setTimestamp(System.currentTimeMillis())
                .setBody(bodyJson)
                .build();
            
            // 3. 发送消息（同步调用）
            GameResponse response = blockingStub.sendGameMessage(message);
            
            if (response.getCode() == 0) {
                logger.debug("📤 gRPC 消息发送成功：playerId={}, gameId={}", playerId, gameId);
                return true;
            } else {
                logger.warn("⚠️ gRPC 消息发送失败：code={}, message={}", response.getCode(), response.getMessage());
                return false;
            }
            
        } catch (StatusRuntimeException e) {
            logger.error("❌ gRPC 调用异常：{} - {}", e.getStatus(), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("❌ 发送游戏消息失败：{}", e.getMessage());
            return false;
        }
    }
    
    /**
     * 启动心跳（双向流）
     */
    private void startHeartbeat() {
        // 创建双向流
        this.heartbeatRequestObserver = asyncStub.heartbeat(new StreamObserver<HeartbeatResponse>() {
            @Override
            public void onNext(HeartbeatResponse response) {
                logger.debug("💓 收到 gRPC 心跳响应：code={}, serverTime={}", response.getCode(), response.getServerTime());
            }
            
            @Override
            public void onError(Throwable t) {
                logger.error("❌ gRPC 心跳错误：{}", t.getMessage());
            }
            
            @Override
            public void onCompleted() {
                logger.info("🔚 gRPC 心跳流完成");
            }
        });
        
        logger.info("✅ gRPC 心跳已启动");
    }
    
    /**
     * 发送心跳
     */
    public void sendHeartbeat() {
        if (heartbeatRequestObserver != null) {
            try {
                HeartbeatRequest request = HeartbeatRequest.newBuilder()
                    .setGateId(gateConfig.getId())
                    .setTimestamp(System.currentTimeMillis())
                    .build();
                heartbeatRequestObserver.onNext(request);
            } catch (Exception e) {
                logger.warn("⚠️ 发送心跳失败：{}", e.getMessage());
            }
        }
    }
    
    /**
     * 关闭 gRPC 客户端
     */
    @PreDestroy
    public void shutdown() {
        logger.info("=== 关闭 gRPC 客户端 ===");
        
        // 完成心跳流
        if (heartbeatRequestObserver != null) {
            heartbeatRequestObserver.onCompleted();
        }
        
        // 关闭通道
        if (channel != null) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                channel.shutdownNow();
            }
        }
        
        logger.info("✅ gRPC 客户端已关闭");
    }
}

package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.resilience.ExponentialBackoff;
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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Game gRPC 客户端连接池
 * 
 * 功能说明：
 * 1. 维护多个 Game 服务的 gRPC 长连接
 * 2. 根据 gameId 路由到对应的 Game 服务
 * 3. 支持动态添加/移除 Game 服务实例
 * 4. 支持Stream双向流通信
 * 
 * 架构说明：
 * ┌─────────────────────────────────────────┐
 * │         GameGrpcClientPool              │
 * │                                         │
 * │  Map<gameId, GrpcConnection>            │
 * │    ├─ 1001 → Channel → Game-1          │
 * │    ├─ 1002 → Channel → Game-2          │
 * │    └─ 1003 → Channel → Game-3          │
 * └─────────────────────────────────────────┘
 * 
 * 配置示例（application.yml）：
 * gate:
 *   games:
 *     - id: 1001
 *       host: localhost
 *       port: 9091
 *     - id: 1002
 *       host: localhost
 *       port: 9092
 * 
 * Stream通信说明：
 * - 每个Game服务建立双向Stream连接
 * - 通过Stream发送和接收游戏消息
 * - 支持双向实时通信
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@Component
public class GameGrpcClientPool {

    private static final Logger logger = LoggerFactory.getLogger(GameGrpcClientPool.class);

    private final GateConfig gateConfig;
    private final ObjectMapper objectMapper;
    
    /**
     * Stream消息处理器回调
     */
    private StreamMessageHandler streamMessageHandler;
    
    /**
     * 连接池
     * Key: gameId
     * Value: GrpcConnection（包含 Channel 和 Stub）
     */
    private final Map<Integer, GrpcConnection> connectionPool = new ConcurrentHashMap<>();

    /**
     * 重连调度器（替代 Thread.sleep）
     */
    private volatile ScheduledExecutorService reconnectScheduler;
    
    /**
     * gRPC 连接封装
     */
    private static class GrpcConnection {
        final int gameId;
        final String host;
        final int port;
        final ManagedChannel channel;
        final GameServiceGrpc.GameServiceBlockingStub blockingStub;
        final GameServiceGrpc.GameServiceStub asyncStub;
        
        // Stream通信
        StreamObserver<GameMessage> gameStreamSender;
        StreamObserver<HeartbeatRequest> heartbeatObserver;
        
        // 连接状态
        volatile boolean streamConnected = false;

        // 指数退避（每个连接独立）
        volatile ExponentialBackoff backoff;

        GrpcConnection(int gameId, String host, int port, ManagedChannel channel, ExponentialBackoff backoff) {
            this.gameId = gameId;
            this.host = host;
            this.port = port;
            this.channel = channel;
            this.backoff = backoff;
            this.blockingStub = GameServiceGrpc.newBlockingStub(channel);
            this.asyncStub = GameServiceGrpc.newStub(channel);
        }
    }
    
    /**
     * Stream消息处理器接口
     */
    public interface StreamMessageHandler {
        void onMessageReceived(GameMessage message);
    }
    
    /**
     * 构造函数
     */
    public GameGrpcClientPool(GateConfig gateConfig, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 设置Stream消息处理器
     */
    public void setStreamMessageHandler(StreamMessageHandler handler) {
        this.streamMessageHandler = handler;
    }
    
    /**
     * 初始化连接池
     * 从配置中读取所有 Game 服务信息并建立连接
     */
    @PostConstruct
    public void init() {
        logger.info("=== 初始化 gRPC 连接池 ===");

        reconnectScheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "grpc-reconnect");
            t.setDaemon(true);
            return t;
        });

        // 从配置中读取 Game 服务列表
        for (GateConfig.GameInstance game : gateConfig.getGames()) {
            addConnection(game.getId(), game.getHost(), game.getPort());
        }

        logger.info("✅ gRPC 连接池初始化完成，连接数：{}", connectionPool.size());
    }
    
    /**
     * 添加 Game 服务连接
     * 
     * @param gameId 游戏 ID
     * @param host 主机地址
     * @param port gRPC 端口
     */
    public void addConnection(int gameId, String host, int port) {
        if (connectionPool.containsKey(gameId)) {
            logger.warn("⚠️ Game {} 已存在连接，跳过", gameId);
            return;
        }
        
        logger.info("🔗 创建 Game {} 连接：{}:{}", gameId, host, port);
        
        GateConfig.GrpcPoolConfig poolConfig = gateConfig.getGrpcPool();
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder
            .forAddress(host, port)
            .keepAliveTime(poolConfig.getKeepAliveTime(), TimeUnit.SECONDS)
            .keepAliveTimeout(poolConfig.getKeepAliveTimeout(), TimeUnit.SECONDS)
            .keepAliveWithoutCalls(poolConfig.isKeepAliveWithoutCalls());

        if (gateConfig.getTls().isEnabled()) {
            builder.useTransportSecurity();
            logger.info("gRPC TLS enabled for game {}", gameId);
        } else {
            builder.usePlaintext();
        }

        ManagedChannel channel = builder.build();

        GateConfig.GrpcPoolConfig poolCfg = gateConfig.getGrpcPool();
        ExponentialBackoff backoff = new ExponentialBackoff(
            poolCfg.getReconnectDelay(),
            poolCfg.getReconnectMaxDelay(),
            poolCfg.getReconnectMultiplier()
        );

        // 创建连接对象
        GrpcConnection conn = new GrpcConnection(gameId, host, port, channel, backoff);
        connectionPool.put(gameId, conn);
        
        // 启动Stream连接
        startStreamCommunication(conn);
        
        // 启动心跳
        startHeartbeat(conn);
        
        logger.info("✅ Game {} 连接已建立", gameId);
    }
    
    /**
     * 移除 Game 服务连接
     * 
     * @param gameId 游戏 ID
     */
    public void removeConnection(int gameId) {
        GrpcConnection conn = connectionPool.remove(gameId);
        if (conn != null) {
            logger.info("🔌 移除 Game {} 连接", gameId);
            
            // 关闭Stream
            if (conn.gameStreamSender != null) {
                conn.gameStreamSender.onCompleted();
            }
            
            // 完成心跳流
            if (conn.heartbeatObserver != null) {
                conn.heartbeatObserver.onCompleted();
            }
            
            // 关闭通道
            try {
                conn.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                conn.channel.shutdownNow();
            }
            
            logger.info("✅ Game {} 连接已关闭", gameId);
        }
    }
    
    /**
     * 根据 gameId 获取连接
     * 
     * @param gameId 游戏 ID
     * @return gRPC 连接，不存在返回 null
     */
    private GrpcConnection getConnection(int gameId) {
        return connectionPool.get(gameId);
    }
    
    /**
     * 启动Stream双向流通信
     * 
     * 这是任务2.1的核心实现：建立Bidirectional Stream
     */
    private void startStreamCommunication(GrpcConnection conn) {
        // 使用Bidirectional Stream
        conn.gameStreamSender = conn.asyncStub.streamCommunication(new StreamObserver<GameMessage>() {
            @Override
            public void onNext(GameMessage message) {
                // 收到Game服务端推送的消息
                logger.debug("📥 收到Game Stream消息：gameId={}, playerId={}, msgType={}", 
                    message.getGameId(), message.getPlayerId(), message.getMsgType());
                
                // 回调处理
                if (streamMessageHandler != null) {
                    streamMessageHandler.onMessageReceived(message);
                }
            }
            
            @Override
            public void onError(Throwable t) {
                logger.error("❌ Game {} Stream通信错误：{}", conn.gameId, t.getMessage());
                conn.streamConnected = false;
                
                // 触发重连
                scheduleReconnect(conn);
            }
            
            @Override
            public void onCompleted() {
                logger.info("🔚 Game {} Stream通信完成", conn.gameId);
                conn.streamConnected = false;
            }
        });
        
        conn.streamConnected = true;
        onReconnectSuccess(conn);
        logger.info("✅ Game {} Stream通信已启动", conn.gameId);
    }
    
    /**
     * 调度重连（使用指数退避 + ScheduledExecutorService）
     */
    private void scheduleReconnect(GrpcConnection conn) {
        ScheduledExecutorService scheduler = reconnectScheduler;
        if (scheduler == null || scheduler.isShutdown()) {
            logger.warn("重连调度器已关闭，跳过 Game {} 重连", conn.gameId);
            return;
        }

        long delayMs = conn.backoff.getAndAdvance();
        scheduler.schedule(() -> {
            if (!connectionPool.containsKey(conn.gameId)) {
                return;
            }
            logger.info("🔄 尝试重连 Game {}（delay={}ms）", conn.gameId, delayMs);
            startStreamCommunication(conn);
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 重连成功后重置退避
     */
    private void onReconnectSuccess(GrpcConnection conn) {
        if (conn.backoff != null) {
            conn.backoff.reset();
        }
    }
    
    /**
     * 通过Stream发送游戏消息（任务2.2）
     * 
     * @param gameId 游戏 ID
     * @param playerId 玩家 ID
     * @param msgType 消息类型
     * @param seq 消息序列号
     * @param body 消息体（Java 对象）
     * @return 是否发送成功
     */
    public boolean sendGameMessageViaStream(int gameId, Long playerId, String msgType, int seq, Object body) {
        GrpcConnection conn = getConnection(gameId);
        
        if (conn == null) {
            logger.error("❌ Game {} 连接不存在，无法发送消息", gameId);
            return false;
        }
        
        // 检查Stream是否已连接
        if (!conn.streamConnected || conn.gameStreamSender == null) {
            logger.warn("⚠️ Game {} Stream未连接，尝试使用阻塞式调用", gameId);
            return sendGameMessage(gameId, playerId, msgType, seq, body);
        }
        
        try {
            // 1. 将消息体转换为 JSON 字符串，再转为二进制
            String bodyJson = objectMapper.writeValueAsString(body);
            
            // 2. 构建 gRPC 消息（二进制格式）
            GameMessage message = GameMessage.newBuilder()
                .setGateId(gateConfig.getId())
                .setPlayerId(playerId)
                .setGameId(gameId)
                .setMsgType(msgType != null ? msgType : "unknown")
                .setSeq(seq)
                .setTimestamp(System.currentTimeMillis())
                .setBody(com.google.protobuf.ByteString.copyFromUtf8(bodyJson))
                .build();
            
            // 3. 通过Stream发送（异步）
            conn.gameStreamSender.onNext(message);
            
            logger.debug("📤 Stream消息发送成功：gameId={}, playerId={}", gameId, playerId);
            return true;
            
        } catch (Exception e) {
            logger.error("❌ Stream消息发送失败：gameId={}, {}", gameId, e.getMessage());
            return false;
        }
    }
    
    /**
     * 发送游戏消息（阻塞式，备用方案）
     */
    public boolean sendGameMessage(int gameId, Long playerId, String msgType, int seq, Object body) {
        GrpcConnection conn = getConnection(gameId);
        
        if (conn == null) {
            logger.error("❌ Game {} 连接不存在，无法发送消息", gameId);
            return false;
        }
        
        try {
            // 1. 将消息体转换为 JSON 字符串，再转为二进制
            String bodyJson = objectMapper.writeValueAsString(body);
            
            // 2. 构建 gRPC 消息（二进制格式）
            GameMessage message = GameMessage.newBuilder()
                .setGateId(gateConfig.getId())
                .setPlayerId(playerId)
                .setGameId(gameId)
                .setMsgType(msgType != null ? msgType : "unknown")
                .setSeq(seq)
                .setTimestamp(System.currentTimeMillis())
                .setBody(com.google.protobuf.ByteString.copyFromUtf8(bodyJson))
                .build();
            
            // 3. 发送消息（同步调用）
            GameResponse response = conn.blockingStub.sendGameMessage(message);
            
            if (response.getCode() == 0) {
                logger.debug("📤 gRPC 消息发送成功：gameId={}, playerId={}", gameId, playerId);
                return true;
            } else {
                logger.warn("⚠️ gRPC 消息发送失败：code={}, message={}", response.getCode(), response.getMessage());
                return false;
            }
            
        } catch (StatusRuntimeException e) {
            logger.error("❌ gRPC 调用异常：gameId={}, {} - {}", gameId, e.getStatus(), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("❌ 发送游戏消息失败：gameId={}, {}", gameId, e.getMessage());
            return false;
        }
    }
    
    /**
     * 启动心跳（双向流）
     */
    private void startHeartbeat(GrpcConnection conn) {
        conn.heartbeatObserver = conn.asyncStub.heartbeat(new StreamObserver<HeartbeatResponse>() {
            @Override
            public void onNext(HeartbeatResponse response) {
                logger.debug("💓 Game {} 心跳响应：code={}, serverTime={}", 
                    conn.gameId, response.getCode(), response.getServerTime());
            }
            
            @Override
            public void onError(Throwable t) {
                // 心跳流断开可能是由于连接关闭导致的，这是正常情况
                // 不再打印error日志，避免日志噪音
                logger.warn("⚠️ Game {} 心跳流断开：{} (可能是连接关闭导致)", conn.gameId, t.getMessage());
            }
            
            @Override
            public void onCompleted() {
                logger.info("🔚 Game {} 心跳流完成", conn.gameId);
            }
        });
        
        logger.debug("✅ Game {} 心跳已启动", conn.gameId);
    }
    
    /**
     * 发送心跳
     */
    public void sendHeartbeat(int gameId) {
        GrpcConnection conn = getConnection(gameId);
        if (conn != null && conn.heartbeatObserver != null) {
            try {
                HeartbeatRequest request = HeartbeatRequest.newBuilder()
                    .setGateId(gateConfig.getId())
                    .setTimestamp(System.currentTimeMillis())
                    .build();
                conn.heartbeatObserver.onNext(request);
            } catch (Exception e) {
                logger.warn("⚠️ 发送心跳失败：gameId={}, {}", gameId, e.getMessage());
            }
        }
    }
    
    /**
     * 检查指定 gameId 是否已有连接
     */
    public boolean hasConnection(int gameId) {
        return connectionPool.containsKey(gameId);
    }

    /**
     * 获取池中所有 gameId
     */
    public Set<Integer> getGameIds() {
        return new java.util.HashSet<>(connectionPool.keySet());
    }

    /**
     * 获取连接池大小
     */
    public int getPoolSize() {
        return connectionPool.size();
    }
    
    /**
     * 关闭所有连接
     */
    @PreDestroy
    public void shutdown() {
        logger.info("=== 关闭 gRPC 连接池 ===");

        ScheduledExecutorService scheduler = reconnectScheduler;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // 关闭所有连接
        for (Integer gameId : connectionPool.keySet()) {
            removeConnection(gameId);
        }

        logger.info("✅ gRPC 连接池已关闭");
    }
}

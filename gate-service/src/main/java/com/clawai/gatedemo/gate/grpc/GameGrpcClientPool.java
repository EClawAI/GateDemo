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
 * 连接来源：
 * Game 实例由 {@link com.clawai.gatedemo.gate.service.GameDiscoveryService}
 * 从 Redis 注册表动态发现，通过 {@link #addConnection}/{@link #removeConnection} 维护池。
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
     * 下行消息回调：Game 经双向流推送至网关时触发；可能由 Netty/gRPC 线程调用，实现方需注意线程安全。
     */
    private StreamMessageHandler streamMessageHandler;

    /**
     * gameId → 单条物理连接（含 channel、stub、流状态）；{@link ConcurrentHashMap} 支持并发增删。
     */
    private final Map<Integer, GrpcConnection> connectionPool = new ConcurrentHashMap<>();

    /**
     * 异步执行指数退避重连，避免在回调线程中阻塞 sleep；关闭池时会 shutdown。
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

        /** 业务双向流上行端：向 Game 侧 {@code onNext} 发消息；错误时会触发重连调度。 */
        StreamObserver<GameMessage> gameStreamSender;
        /** 心跳流上行端：{@link GameGrpcClientPool#sendHeartbeat(int)} 经此发送。 */
        StreamObserver<HeartbeatRequest> heartbeatObserver;

        /** 当前业务流是否已建立且视为可用；断流或 onError 时置 false。 */
        volatile boolean streamConnected = false;

        /** 本 game 实例重连间隔策略，重连成功后重置。 */
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
     * Game 经 gRPC 双向流推送到网关时的回调接口。
     */
    public interface StreamMessageHandler {
        /**
         * 收到一条 Game 下发的消息。
         *
         * @param message 含 gameId、playerId、msgType、body 等；勿长期阻塞该线程
         */
        void onMessageReceived(GameMessage message);
    }

    /**
     * @param gateConfig    本机网关 ID、Game 列表、gRPC/TLS 等配置
     * @param objectMapper  将消息体对象序列化为 JSON 写入 protobuf
     */
    public GameGrpcClientPool(GateConfig gateConfig, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.objectMapper = objectMapper;
    }
    
    /**
     * 注册下行消息处理器；通常在启动阶段注入，与业务 WebSocket 转发衔接。
     *
     * @param handler 可为 null 表示暂不处理下行推送
     */
    public void setStreamMessageHandler(StreamMessageHandler handler) {
        this.streamMessageHandler = handler;
    }
    
    /**
     * 初始化重连线程池。实际 Game 连接由 {@link com.clawai.gatedemo.gate.service.GameDiscoveryService}
     * 通过 Redis 服务发现后调用 {@link #addConnection} 动态建立。
     */
    @PostConstruct
    public void init() {
        logger.info("=== 初始化 gRPC 连接池 ===");

        reconnectScheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "grpc-reconnect");
            t.setDaemon(true);
            return t;
        });

        logger.info("✅ gRPC 连接池初始化完成，等待服务发现注入连接");
    }
    
    /**
     * 为指定 gameId 新建 {@link ManagedChannel} 并加入池，同时启动业务双向流与心跳流。
     *
     * @param gameId 逻辑游戏实例 ID，与路由一致
     * @param host   gRPC 地址
     * @param port   gRPC 端口
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
     * 从池中移除并关闭 channel，完成 stream/heartbeat；无对应连接时无操作。
     *
     * @param gameId 目标游戏实例 ID
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
     * 优先经业务双向流异步下发；流不可用时回退为 {@link #sendGameMessage(int, Long, String, int, Object)}（阻塞 unary）。
     *
     * @param gameId   路由目标
     * @param playerId 玩家 ID，可为 null 视协议而定
     * @param msgType  业务消息类型字符串
     * @param seq      客户端序列号
     * @param body     将 JSON 序列化后写入 protobuf 消息体
     * @return 入队/发送成功为 true；连接缺失、序列化失败等为 false（仅打日志，不抛业务异常）
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
     * 使用阻塞 stub 同步调用 {@code sendGameMessage}，作为流不可用时的兜底。
     *
     * @param gameId   路由目标
     * @param playerId 玩家 ID
     * @param msgType  业务类型
     * @param seq      序号
     * @param body     将序列化为 JSON 写入消息
     * @return Game 返回 code==0 为 true；RPC 异常或非 0 为 false
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
     * 向指定 game 的心跳流发送一帧；连接或流未就绪时静默跳过（仅 warn）。
     *
     * @param gameId 目标实例
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
     * @param gameId 游戏实例 ID
     * @return 池中是否存在该键（不代表流一定健康）
     */
    public boolean hasConnection(int gameId) {
        return connectionPool.containsKey(gameId);
    }

    /**
     * @return 当前池内 gameId 快照（拷贝自 keySet，并发下可能瞬时不一致）
     */
    public Set<Integer> getGameIds() {
        return new java.util.HashSet<>(connectionPool.keySet());
    }

    /**
     * @return 连接池中当前 gameId 条目数量
     */
    public int getPoolSize() {
        return connectionPool.size();
    }
    
    /**
     * 关闭重连调度器并逐个 {@link #removeConnection(int)}；供容器销毁时调用。
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

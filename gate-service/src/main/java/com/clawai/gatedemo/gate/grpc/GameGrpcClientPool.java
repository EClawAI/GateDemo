package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.resilience.ExponentialBackoff;
import com.clawai.gatedemo.gate.service.GameDiscoveryService;
import com.clawai.gatedemo.grpc.*;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Game gRPC 客户端连接池：对齐 lzwSLG/bsserver/icefire-gate 上游策略的 gRPC 实现。
 *
 * <ul>
 *   <li>引用计数：每个已认证会话在目标 game 上占用 1；会话断开时释放。</li>
 *   <li>按需建连：{@code gate.grpc-pool.lazy-connect=true} 时由 {@link #ensureConnectedAsync(int)} 建连。</li>
 *   <li>空闲回收：引用为 0 且空闲超过阈值时由 {@link com.clawai.gatedemo.gate.grpc.GrpcIdleConnectionSweeper} 关闭。</li>
 * </ul>
 */
@Component
public class GameGrpcClientPool {

    private static final Logger logger = LoggerFactory.getLogger(GameGrpcClientPool.class);

    private final GateConfig gateConfig;
    private final ObjectProvider<GameDiscoveryService> discoveryProvider;

    private StreamMessageHandler streamMessageHandler;

    private final Map<Integer, GrpcConnection> connectionPool = new ConcurrentHashMap<>();
    /** 按 gameId 串行化首次建连，对齐 icefire-gate 单路 bootstrap 语义。 */
    private final ConcurrentHashMap<Integer, Object> ensureLocks = new ConcurrentHashMap<>();

    private Object ensureLock(int gameId) {
        return ensureLocks.computeIfAbsent(gameId, k -> new Object());
    }

    private volatile ScheduledExecutorService reconnectScheduler;
    private ExecutorService ensureExecutor;

    public GameGrpcClientPool(GateConfig gateConfig,
                              ObjectProvider<GameDiscoveryService> discoveryProvider) {
        this.gateConfig = gateConfig;
        this.discoveryProvider = discoveryProvider;
    }

    private static final class GrpcConnection {
        private static final Logger CONN_LOG = LoggerFactory.getLogger(GrpcConnection.class);

        final int gameId;
        final String host;
        final int port;
        final ManagedChannel channel;
        final GameServiceGrpc.GameServiceBlockingStub blockingStub;
        final GameServiceGrpc.GameServiceStub asyncStub;

        StreamObserver<GameMessage> gameStreamSender;
        StreamObserver<HeartbeatRequest> heartbeatObserver;

        volatile boolean streamConnected = false;
        volatile ExponentialBackoff backoff;

        /**
         * 对齐 icefire-gate {@code GameConnection#refCount}：会话占用上游 gRPC 条数。
         */
        private final AtomicInteger refCount = new AtomicInteger(0);
        private final AtomicLong idleSinceMillis = new AtomicLong(0);

        GrpcConnection(int gameId, String host, int port, ManagedChannel channel, ExponentialBackoff backoff) {
            this.gameId = gameId;
            this.host = host;
            this.port = port;
            this.channel = channel;
            this.backoff = backoff;
            this.blockingStub = GameServiceGrpc.newBlockingStub(channel);
            this.asyncStub = GameServiceGrpc.newStub(channel);
        }

        int tryIncRef() {
            while (true) {
                int cur = refCount.get();
                if (cur < 0) {
                    return -1;
                }
                if (refCount.compareAndSet(cur, cur + 1)) {
                    idleSinceMillis.set(0);
                    return cur + 1;
                }
            }
        }

        int decRef() {
            int u = refCount.decrementAndGet();
            if (u < 0) {
                CONN_LOG.warn("gRPC refCount < 0 for gameId={}, reset", gameId);
                refCount.set(0);
                u = 0;
            }
            if (u == 0) {
                idleSinceMillis.set(System.currentTimeMillis());
            }
            return u;
        }

        int currentRef() {
            return refCount.get();
        }

        /**
         * Sweeper 将 ref 从 0 CAS 到 {@code Integer.MIN_VALUE} 表示占用回收权。
         */
        boolean tryClaimForSweep() {
            return refCount.compareAndSet(0, Integer.MIN_VALUE);
        }

        long idleSince() {
            return idleSinceMillis.get();
        }

        void markIdleIfNoRefs() {
            if (refCount.get() == 0) {
                idleSinceMillis.set(System.currentTimeMillis());
            }
        }
    }

    public interface StreamMessageHandler {
        void onMessageReceived(GameMessage message);
    }

    public void setStreamMessageHandler(StreamMessageHandler handler) {
        this.streamMessageHandler = handler;
    }

    @PostConstruct
    public void init() {
        logger.info("=== 初始化 gRPC 连接池 ===");

        reconnectScheduler = new ScheduledThreadPoolExecutor(2, r -> {
            Thread t = new Thread(r, "grpc-reconnect");
            t.setDaemon(true);
            return t;
        });

        ensureExecutor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "gate-grpc-ensure");
            t.setDaemon(true);
            return t;
        });

        logger.info("✅ gRPC 连接池初始化完成");
    }

    /**
     * 在会话认证成功后为某 game 增加引用；须保证目标 channel 已存在或已通过 {@link #ensureConnectedBlocking(int)}。
     */
    public void acquireRef(int gameId) {
        GrpcConnection c = connectionPool.get(gameId);
        if (c == null) {
            logger.warn("acquireRef: no connection for gameId={}", gameId);
            return;
        }
        int n = c.tryIncRef();
        if (n < 0) {
            logger.warn("acquireRef: connection for gameId={} is being reclaimed", gameId);
        }
    }

    /**
     * 会话关闭时释放引用；引用到 0 时开始累计空闲时间供 Sweeper 回收。
     */
    public void releaseRef(int gameId) {
        GrpcConnection c = connectionPool.get(gameId);
        if (c == null) {
            return;
        }
        int n = c.decRef();
        logger.debug("releaseRef gameId={} -> refCount={}", gameId, n);
    }

    /**
     * 非阻塞：在 {@link #ensureExecutor} 上执行建连，结果回到 Netty 线程完成认证后续逻辑。
     */
    public CompletableFuture<Boolean> ensureConnectedAsync(int gameId) {
        return CompletableFuture.supplyAsync(() -> ensureConnectedBlocking(gameId), ensureExecutor);
    }

    private boolean ensureConnectedBlocking(int gameId) {
        GameDiscoveryService ds = discoveryProvider.getIfAvailable();
        if (ds == null || !gateConfig.getDiscovery().isEnabled()) {
            return connectionPool.containsKey(gameId);
        }
        synchronized (ensureLock(gameId)) {
            GrpcConnection existing = connectionPool.get(gameId);
            if (existing != null && existing.streamConnected) {
                return true;
            }
            GameDiscoveryService.GameInstance inst = ds.getGameInstance(gameId);
            if (inst == null || !inst.isAvailable()) {
                logger.warn("ensureConnected: gameId={} not registered or unavailable", gameId);
                return false;
            }
            if (!connectionPool.containsKey(gameId)) {
                addConnection(gameId, inst.getHost(), inst.getPort());
            }
            GrpcConnection c = connectionPool.get(gameId);
            return c != null && c.streamConnected;
        }
    }

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

        ExponentialBackoff backoff = new ExponentialBackoff(
                poolConfig.getReconnectDelay(),
                poolConfig.getReconnectMaxDelay(),
                poolConfig.getReconnectMultiplier()
        );

        GrpcConnection conn = new GrpcConnection(gameId, host, port, channel, backoff);
        connectionPool.put(gameId, conn);

        startStreamCommunication(conn);
        startHeartbeat(conn);
        conn.markIdleIfNoRefs();

        logger.info("✅ Game {} 连接已建立", gameId);
    }

    public void removeConnection(int gameId) {
        GrpcConnection conn = connectionPool.remove(gameId);
        if (conn != null) {
            closeGrpcConnection(conn);
        }
    }

    private void closeGrpcConnection(GrpcConnection conn) {
        logger.info("🔌 移除 Game {} 连接", conn.gameId);

        if (conn.gameStreamSender != null) {
            conn.gameStreamSender.onCompleted();
        }
        if (conn.heartbeatObserver != null) {
            conn.heartbeatObserver.onCompleted();
        }
        try {
            conn.channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            conn.channel.shutdownNow();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            conn.channel.shutdownNow();
        }
        logger.info("✅ Game {} 连接已关闭", conn.gameId);
    }

    /**
     * 周期巡检：对齐 icefire-gate {@code IdleConnectionSweeper}，关闭 refCount=0 且空闲超阈值的 gRPC。
     */
    public void sweepIdleUpstreamConnections() {
        GateConfig.GrpcPoolConfig cfg = gateConfig.getGrpcPool();
        long thresholdMs = TimeUnit.SECONDS.toMillis(Math.max(1, cfg.getIdleCloseSeconds()));
        long now = System.currentTimeMillis();

        for (Map.Entry<Integer, GrpcConnection> e : connectionPool.entrySet()) {
            GrpcConnection conn = e.getValue();
            try {
                if (conn.currentRef() > 0) {
                    continue;
                }
                long idleSince = conn.idleSince();
                if (idleSince <= 0) {
                    continue;
                }
                if (now - idleSince < thresholdMs) {
                    continue;
                }
                if (!conn.tryClaimForSweep()) {
                    continue;
                }
                connectionPool.remove(e.getKey(), conn);
                closeGrpcConnection(conn);
                logger.info("空闲回收 gRPC：gameId={}, idleMs={}", e.getKey(), now - idleSince);
            } catch (Exception ex) {
                logger.warn("sweep error gameId={}", e.getKey(), ex);
            }
        }
    }

    private GrpcConnection getConnection(int gameId) {
        return connectionPool.get(gameId);
    }

    private void startStreamCommunication(GrpcConnection conn) {
        conn.gameStreamSender = conn.asyncStub.streamCommunication(new StreamObserver<GameMessage>() {
            @Override
            public void onNext(GameMessage message) {
                logger.debug("收到 Game Stream 消息: gameId={}, playerId={}, msgId={}",
                        conn.gameId, message.getPlayerId(), message.getMsgId());
                if (streamMessageHandler != null) {
                    streamMessageHandler.onMessageReceived(message);
                }
            }

            @Override
            public void onError(Throwable t) {
                logger.error("❌ Game {} Stream通信错误：{}", conn.gameId, t.getMessage());
                conn.streamConnected = false;
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

    private void onReconnectSuccess(GrpcConnection conn) {
        if (conn.backoff != null) {
            conn.backoff.reset();
        }
    }

    public boolean sendGameMessageViaStream(int gameId, Long playerId, int msgId, int seq, byte[] body) {
        GrpcConnection conn = getConnection(gameId);

        if (conn == null) {
            logger.error("Game {} 连接不存在，无法发送消息", gameId);
            return false;
        }

        if (!conn.streamConnected || conn.gameStreamSender == null) {
            logger.warn("Game {} Stream 未连接，尝试阻塞式调用", gameId);
            return sendGameMessage(gameId, playerId, msgId, seq, body);
        }

        try {
            GameMessage message = GameMessage.newBuilder()
                    .setGateId(gateConfig.getId())
                    .setPlayerId(playerId)
                    .setMsgId(msgId)
                    .setSeq(seq)
                    .setTimestamp(System.currentTimeMillis())
                    .setBody(ByteString.copyFrom(body != null ? body : new byte[0]))
                    .build();

            conn.gameStreamSender.onNext(message);

            logger.debug("Stream 消息发送成功: gameId={}, playerId={}, msgId={}", gameId, playerId, msgId);
            return true;

        } catch (Exception e) {
            logger.error("Stream 消息发送失败: gameId={}, {}", gameId, e.getMessage());
            return false;
        }
    }

    public boolean sendGameMessage(int gameId, Long playerId, int msgId, int seq, byte[] body) {
        GrpcConnection conn = getConnection(gameId);

        if (conn == null) {
            logger.error("Game {} 连接不存在，无法发送消息", gameId);
            return false;
        }

        try {
            GameMessage message = GameMessage.newBuilder()
                    .setGateId(gateConfig.getId())
                    .setPlayerId(playerId)
                    .setMsgId(msgId)
                    .setSeq(seq)
                    .setTimestamp(System.currentTimeMillis())
                    .setBody(ByteString.copyFrom(body != null ? body : new byte[0]))
                    .build();

            GameResponse response = conn.blockingStub.sendGameMessage(message);

            if (response.getCode() == 0) {
                logger.debug("gRPC 消息发送成功: gameId={}, playerId={}, msgId={}", gameId, playerId, msgId);
                return true;
            } else {
                logger.warn("gRPC 消息发送失败: code={}, message={}", response.getCode(), response.getMessage());
                return false;
            }

        } catch (StatusRuntimeException e) {
            logger.error("gRPC 调用异常: gameId={}, {} - {}", gameId, e.getStatus(), e.getMessage());
            return false;
        } catch (Exception e) {
            logger.error("发送游戏消息失败: gameId={}, {}", gameId, e.getMessage());
            return false;
        }
    }

    private void startHeartbeat(GrpcConnection conn) {
        conn.heartbeatObserver = conn.asyncStub.heartbeat(new StreamObserver<HeartbeatResponse>() {
            @Override
            public void onNext(HeartbeatResponse response) {
                logger.debug("💓 Game {} 心跳响应：code={}, serverTime={}",
                        conn.gameId, response.getCode(), response.getServerTime());
            }

            @Override
            public void onError(Throwable t) {
                logger.warn("⚠️ Game {} 心跳流断开：{} (可能是连接关闭导致)", conn.gameId, t.getMessage());
            }

            @Override
            public void onCompleted() {
                logger.info("🔚 Game {} 心跳流完成", conn.gameId);
            }
        });

        logger.debug("✅ Game {} 心跳已启动", conn.gameId);
    }

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

    public boolean hasConnection(int gameId) {
        return connectionPool.containsKey(gameId);
    }

    public Set<Integer> getGameIds() {
        return new java.util.HashSet<>(connectionPool.keySet());
    }

    public int getPoolSize() {
        return connectionPool.size();
    }

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

        if (ensureExecutor != null) {
            ensureExecutor.shutdown();
            try {
                if (!ensureExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    ensureExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ensureExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        for (Integer gameId : connectionPool.keySet()) {
            removeConnection(gameId);
        }

        logger.info("✅ gRPC 连接池已关闭");
    }
}

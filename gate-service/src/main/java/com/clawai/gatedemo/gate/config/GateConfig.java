package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Gate 服务配置类
 * 通过 @ConfigurationProperties 从配置文件中加载以 "gate" 为前缀的配置项
 */
@Configuration
@ConfigurationProperties(prefix = "gate")
public class GateConfig {

    /** Gate 实例唯一标识 */
    private String id = "gate-01";
    /** Gate 服务监听地址 */
    private String host = "0.0.0.0";
    /** Gate 服务 HTTP 监听端口 */
    private int port = 8888;
    /** 玩家相关配置 */
    private Player player = new Player();
    /** Login 服务配置 */
    private LoginService loginService = new LoginService();
    /** 服务发现配置 */
    private DiscoveryConfig discovery = new DiscoveryConfig();
    /** gRPC 连接池配置 */
    private GrpcPoolConfig grpcPool = new GrpcPoolConfig();
    /** Redis 配置 */
    private RedisConfig redis = new RedisConfig();
    /** 健康检查配置 */
    private HealthConfig health = new HealthConfig();
    /** TLS/SSL 配置 */
    private TlsConfig tls = new TlsConfig();
    /** TCP 端口配置 */
    private TcpConfig tcp = new TcpConfig();
    /** 集群配置 */
    private ClusterConfig cluster = new ClusterConfig();
    /** Flow（双层 Session 模型）配置 */
    private FlowConfig flow = new FlowConfig();

    /**
     * TCP 端口配置
     * 用于支持玩家通过 TCP 长连接方式接入 Gate 服务
     */
    public static class TcpConfig {
        /** 是否启用 TCP 端口 */
        private boolean enabled = false;
        /** TCP 监听端口 */
        private int port = 9999;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * 集群配置
     * 用于 Gate 节点之间的集群管理和自动发现
     */
    public static class ClusterConfig {
        /** 是否启用集群功能 */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    /**
     * 健康检查配置
     * 用于对外提供健康检查端点
     */
    public static class HealthConfig {
        /** 健康检查服务端口 */
        private int port = 8890;
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * TLS/SSL 安全传输配置
     * 用于启用 HTTPS 或 WSS 等安全传输协议
     */
    public static class TlsConfig {
        /** 是否启用 TLS */
        private boolean enabled = false;
        /** 证书文件路径 */
        private String certPath;
        /** 私钥文件路径 */
        private String keyPath;
        /** keystore 文件路径（用于 PKCS12 或 JKS 格式） */
        private String keystorePath;
        /** keystore 密码 */
        private String keystorePassword;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCertPath() { return certPath; }
        public void setCertPath(String certPath) { this.certPath = certPath; }
        public String getKeyPath() { return keyPath; }
        public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
        public String getKeystorePath() { return keystorePath; }
        public void setKeystorePath(String keystorePath) { this.keystorePath = keystorePath; }
        public String getKeystorePassword() { return keystorePassword; }
        public void setKeystorePassword(String keystorePassword) { this.keystorePassword = keystorePassword; }
    }

    /**
     * 玩家相关配置
     * 包括玩家心跳等相关参数
     */
    public static class Player {
        /** 玩家心跳间隔（秒） */
        private int heartbeatInterval = 60;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    /**
     * Login 服务配置
     * Gate 服务需要连接 Login 服务进行认证
     */
    public static class LoginService {
        /** Login 服务主机地址 */
        private String host = "localhost";
        /** Login 服务端口，需与 login-service 的 server.port 一致 */
        private int port = 9086;
        /** Login 服务心跳间隔（秒） */
        private int heartbeatInterval = 30;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    /**
     * 服务发现配置
     * 用于从注册中心发现其他服务
     */
    public static class DiscoveryConfig {
        /** 是否启用服务发现 */
        private boolean enabled = true;
        /** 初始加载超时时间（毫秒） */
        private int initialLoadTimeout = 5000;
        /** 过期数据阈值（毫秒），超过此时间的数据视为过期 */
        private long staleThreshold = 120000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getInitialLoadTimeout() { return initialLoadTimeout; }
        public void setInitialLoadTimeout(int initialLoadTimeout) { this.initialLoadTimeout = initialLoadTimeout; }
        public long getStaleThreshold() { return staleThreshold; }
        public void setStaleThreshold(long staleThreshold) { this.staleThreshold = staleThreshold; }
    }

    /**
     * gRPC 连接池配置
     * 用于管理 Gate 到 Game 服务的 gRPC 连接
     */
    public static class GrpcPoolConfig {
        /** 空闲连接保活时间（秒） */
        private long keepAliveTime = 30;
        /** 保活超时时间（秒） */
        private long keepAliveTimeout = 10;
        /** 是否允许在没有调用时发送保活探测 */
        private boolean keepAliveWithoutCalls = true;
        /** 重连初始延迟（毫秒） */
        private long reconnectDelay = 5000;
        /** 重连最大延迟（毫秒） */
        private long reconnectMaxDelay = 60000;
        /** 重连延迟倍数 */
        private double reconnectMultiplier = 2.0;
        /** 心跳间隔（毫秒） */
        private int heartbeatInterval = 30000;
        /**
         * 为 true 时：服务发现只更新 game 元数据，不向 game 建 gRPC；首个需要转发的会话在认证后按需建连。
         * 对齐 icefire-gate 的 LazyGameConnectionPool 策略。
         */
        private boolean lazyConnect = false;
        /**
         * 某 game 的 gRPC 连接在引用计数为 0 后持续空闲超过该秒数则关闭（由空闲巡检线程执行）。
         */
        private int idleCloseSeconds = 300;
        /** 空闲连接巡检周期（秒） */
        private int idleSweepIntervalSeconds = 30;

        public long getKeepAliveTime() { return keepAliveTime; }
        public void setKeepAliveTime(long keepAliveTime) { this.keepAliveTime = keepAliveTime; }
        public long getKeepAliveTimeout() { return keepAliveTimeout; }
        public void setKeepAliveTimeout(long keepAliveTimeout) { this.keepAliveTimeout = keepAliveTimeout; }
        public boolean isKeepAliveWithoutCalls() { return keepAliveWithoutCalls; }
        public void setKeepAliveWithoutCalls(boolean keepAliveWithoutCalls) { this.keepAliveWithoutCalls = keepAliveWithoutCalls; }
        public long getReconnectDelay() { return reconnectDelay; }
        public void setReconnectDelay(long reconnectDelay) { this.reconnectDelay = reconnectDelay; }
        public long getReconnectMaxDelay() { return reconnectMaxDelay; }
        public void setReconnectMaxDelay(long reconnectMaxDelay) { this.reconnectMaxDelay = reconnectMaxDelay; }
        public double getReconnectMultiplier() { return reconnectMultiplier; }
        public void setReconnectMultiplier(double reconnectMultiplier) { this.reconnectMultiplier = reconnectMultiplier; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public boolean isLazyConnect() { return lazyConnect; }
        public void setLazyConnect(boolean lazyConnect) { this.lazyConnect = lazyConnect; }
        public int getIdleCloseSeconds() { return idleCloseSeconds; }
        public void setIdleCloseSeconds(int idleCloseSeconds) { this.idleCloseSeconds = idleCloseSeconds; }
        public int getIdleSweepIntervalSeconds() { return idleSweepIntervalSeconds; }
        public void setIdleSweepIntervalSeconds(int idleSweepIntervalSeconds) {
            this.idleSweepIntervalSeconds = idleSweepIntervalSeconds;
        }
    }

    /**
     * Redis 配置
     * 用于 Gate 服务的缓存、会话存储等功能
     */
    public static class RedisConfig {
        /** Redis 主机地址 */
        private String host = "localhost";
        /** Redis 端口 */
        private int port = 6379;
        /** Redis 6+ ACL 用户名；空表示仅密码认证 */
        private String username = "";
        /** Redis 密码（空字符串表示无密码） */
        private String password = "";
        /** Redis 数据库编号 */
        private int database = 0;
        /** Redis Sentinel 配置 */
        private SentinelConfig sentinel = new SentinelConfig();

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public SentinelConfig getSentinel() { return sentinel; }
        public void setSentinel(SentinelConfig sentinel) { this.sentinel = sentinel; }
    }

    /**
     * Redis Sentinel 配置
     * 用于 Redis 高可用部署
     */
    public static class SentinelConfig {
        /** Sentinel 主节点名称 */
        private String master;
        /** Sentinel 节点列表 */
        private List<String> nodes = new ArrayList<>();

        public String getMaster() { return master; }
        public void setMaster(String master) { this.master = master; }
        public List<String> getNodes() { return nodes; }
        public void setNodes(List<String> nodes) { this.nodes = nodes != null ? nodes : new ArrayList<>(); }
        /**
         * 接受逗号分隔的 host:port 格式（用于环境变量注入，如 REDIS_SENTINEL_NODES）
         * @param nodesStr 逗号分隔的节点字符串
         */
        public void setNodes(String nodesStr) {
            if (nodesStr != null && !nodesStr.trim().isEmpty()) {
                this.nodes = Arrays.stream(nodesStr.split("\\s*,\\s*")).map(String::trim).filter(s -> !s.isEmpty()).toList();
            }
        }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
    public LoginService getLoginService() { return loginService; }
    public void setLoginService(LoginService loginService) { this.loginService = loginService; }
    public DiscoveryConfig getDiscovery() { return discovery; }
    public void setDiscovery(DiscoveryConfig discovery) { this.discovery = discovery; }
    public GrpcPoolConfig getGrpcPool() { return grpcPool; }
    public void setGrpcPool(GrpcPoolConfig grpcPool) { this.grpcPool = grpcPool; }
    public RedisConfig getRedis() { return redis; }
    public void setRedis(RedisConfig redis) { this.redis = redis; }
    public HealthConfig getHealth() { return health; }
    public void setHealth(HealthConfig health) { this.health = health; }
    public TlsConfig getTls() { return tls; }
    public void setTls(TlsConfig tls) { this.tls = tls; }
    public TcpConfig getTcp() { return tcp; }
    public void setTcp(TcpConfig tcp) { this.tcp = tcp; }
    public ClusterConfig getCluster() { return cluster; }
    public void setCluster(ClusterConfig cluster) { this.cluster = cluster; }
    public FlowConfig getFlow() { return flow; }
    public void setFlow(FlowConfig flow) { this.flow = flow; }

    /**
     * FlowSession（双层 Session 模型）相关配置。
     * <p>详见 {@code openspec/changes/add-netun-resume-flow-session/design.md} §4-§5。
     */
    public static class FlowConfig {
        /** DETACHED 后允许 RESUME 的最大秒数；超时则销毁 flow */
        private int detachedTtlSeconds = 60;
        /** Flow 自创建起的最大总寿命（秒）；用于兜底防止 Redis 漂泊 */
        private long maxTtlSeconds = 24L * 60L * 60L;
        /** ATTACHED 状态下网关续期 expiresAt 的间隔（秒） */
        private int renewalIntervalSeconds = 15;
        /** DETACHED 超时扫描间隔（秒） */
        private int detachedScanIntervalSeconds = 5;
        /** Redis Key 前缀，单独可配便于多实例 / 测试隔离 */
        private String redisKeyPrefix = "gate:flow:";
        /** B1：下行 buffer 配置 */
        private BufferConfig buffer = new BufferConfig();
        /** B1：服务端 features 通告配置 */
        private FeaturesConfig features = new FeaturesConfig();
        /** B2：跨实例 owner 迁移 + Pub/Sub eviction 配置 */
        private CrossConfig cross = new CrossConfig();
        /** B3：离线消息合流策略配置 */
        private OfflineConfig offline = new OfflineConfig();
        /** Observability：跨实例 takeover / resume 分桶指标 + cross logger 配置 */
        private ObservabilityConfig observability = new ObservabilityConfig();

        public int getDetachedTtlSeconds() { return detachedTtlSeconds; }
        public void setDetachedTtlSeconds(int detachedTtlSeconds) { this.detachedTtlSeconds = detachedTtlSeconds; }
        public long getMaxTtlSeconds() { return maxTtlSeconds; }
        public void setMaxTtlSeconds(long maxTtlSeconds) { this.maxTtlSeconds = maxTtlSeconds; }
        public int getRenewalIntervalSeconds() { return renewalIntervalSeconds; }
        public void setRenewalIntervalSeconds(int renewalIntervalSeconds) {
            this.renewalIntervalSeconds = renewalIntervalSeconds;
        }
        public int getDetachedScanIntervalSeconds() { return detachedScanIntervalSeconds; }
        public void setDetachedScanIntervalSeconds(int detachedScanIntervalSeconds) {
            this.detachedScanIntervalSeconds = detachedScanIntervalSeconds;
        }
        public String getRedisKeyPrefix() { return redisKeyPrefix; }
        public void setRedisKeyPrefix(String redisKeyPrefix) { this.redisKeyPrefix = redisKeyPrefix; }
        public BufferConfig getBuffer() { return buffer; }
        public void setBuffer(BufferConfig buffer) { this.buffer = buffer == null ? new BufferConfig() : buffer; }
        public FeaturesConfig getFeatures() { return features; }
        public void setFeatures(FeaturesConfig features) { this.features = features == null ? new FeaturesConfig() : features; }
        public CrossConfig getCross() { return cross; }
        public void setCross(CrossConfig cross) { this.cross = cross == null ? new CrossConfig() : cross; }
        public OfflineConfig getOffline() { return offline; }
        public void setOffline(OfflineConfig offline) { this.offline = offline == null ? new OfflineConfig() : offline; }
        public ObservabilityConfig getObservability() { return observability; }
        public void setObservability(ObservabilityConfig observability) {
            this.observability = observability == null ? new ObservabilityConfig() : observability;
        }
    }

    /**
     * Observability：跨实例 takeover / RESUME / replay 分桶指标 + 跨实例事件 logger 控制。
     * <p>详见 {@code openspec/changes/add-flow-observability-buckets/design.md}.
     *
     * <p>所有 sub-switch 在 {@link #enabled} 为 false 时整体失效（master kill switch）。
     * sub-switch 为 false 时对应 Meter 不注册（hot path 零开销）。
     */
    public static class ObservabilityConfig {
        /** Master switch；false 时所有 sub-switch 失效。 */
        private boolean enabled = true;
        /** {@code gate_flow_takeover_total} */
        private boolean takeoverTotalEnabled = true;
        /** {@code gate_flow_resume_total} */
        private boolean resumeTotalEnabled = true;
        /** {@code gate_flow_replay_total} */
        private boolean replayTotalEnabled = true;
        /** 两个 latency Timer */
        private boolean latencyEnabled = true;
        /** {@code gate.cross.event} 顶层 logger */
        private boolean crossLoggerEnabled = true;
        /** SLO bucket 列表（逗号分隔，支持 ms/s 后缀）；默认 10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s. */
        private String histogramSlo = "10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s";
        /** RESUME 总耗时超过此阈值则 emit outcome=timeout（与 succeeded 互斥）。 */
        private long resumeTimeoutMs = 3000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public boolean isTakeoverTotalEnabled() { return takeoverTotalEnabled; }
        public void setTakeoverTotalEnabled(boolean takeoverTotalEnabled) {
            this.takeoverTotalEnabled = takeoverTotalEnabled;
        }
        public boolean isResumeTotalEnabled() { return resumeTotalEnabled; }
        public void setResumeTotalEnabled(boolean resumeTotalEnabled) {
            this.resumeTotalEnabled = resumeTotalEnabled;
        }
        public boolean isReplayTotalEnabled() { return replayTotalEnabled; }
        public void setReplayTotalEnabled(boolean replayTotalEnabled) {
            this.replayTotalEnabled = replayTotalEnabled;
        }
        public boolean isLatencyEnabled() { return latencyEnabled; }
        public void setLatencyEnabled(boolean latencyEnabled) { this.latencyEnabled = latencyEnabled; }
        public boolean isCrossLoggerEnabled() { return crossLoggerEnabled; }
        public void setCrossLoggerEnabled(boolean crossLoggerEnabled) {
            this.crossLoggerEnabled = crossLoggerEnabled;
        }
        public String getHistogramSlo() { return histogramSlo; }
        public void setHistogramSlo(String histogramSlo) { this.histogramSlo = histogramSlo; }
        public long getResumeTimeoutMs() { return resumeTimeoutMs; }
        public void setResumeTimeoutMs(long resumeTimeoutMs) { this.resumeTimeoutMs = resumeTimeoutMs; }
    }

    /**
     * B3：离线消息合流策略配置。
     * <p>详见 {@code openspec/changes/refine-flow-offline-merge-policy/design.md}.
     */
    public static class OfflineConfig {
        /** 主开关；off 时所有 offline API 直接 no-op 返回 false / 0（退化 Phase A/B1 行为）。 */
        private boolean enabled = true;
        /** offline stream 保留天数；写入时设置 TTL。 */
        private int retentionDays = 7;
        /** NEW 登录时若兜底 stream 长度 ≥ 阈值则触发 RELOGIN。 */
        private int reloginThreshold = 200;
        /** RESUME 合流 / NEW drain 时单次 XRANGE batch 上限。 */
        private int replayBatchSize = 100;
        /** destroy 时若 reason == detached_ttl 则把 buffer 中未 ACK 帧 flush 到 offline stream。 */
        private boolean flushOnDestroy = true;
        /** cross-instance evict 时同上。 */
        private boolean flushOnCrossEvict = true;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRetentionDays() { return retentionDays; }
        public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }
        public int getReloginThreshold() { return reloginThreshold; }
        public void setReloginThreshold(int reloginThreshold) { this.reloginThreshold = reloginThreshold; }
        public int getReplayBatchSize() { return replayBatchSize; }
        public void setReplayBatchSize(int replayBatchSize) { this.replayBatchSize = replayBatchSize; }
        public boolean isFlushOnDestroy() { return flushOnDestroy; }
        public void setFlushOnDestroy(boolean flushOnDestroy) { this.flushOnDestroy = flushOnDestroy; }
        public boolean isFlushOnCrossEvict() { return flushOnCrossEvict; }
        public void setFlushOnCrossEvict(boolean flushOnCrossEvict) { this.flushOnCrossEvict = flushOnCrossEvict; }
    }

    /**
     * B2：跨实例 owner 迁移 + Pub/Sub eviction 配置。
     * <p>详见 {@code openspec/changes/add-cross-instance-flow-takeover/design.md}.
     */
    public static class CrossConfig {
        /** 主开关：off 时退回 Phase A 的 {@code REJECTED_OWNER_OTHER} 早退。 */
        private boolean enabled = true;
        /** Pub/Sub 通道名，默认 {@code gate:flow:evict}. */
        private String evictChannel = "gate:flow:evict";
        /** 调试用：是否发布 self → self 的 evict（一般保持 false）。 */
        private boolean publishSelfEvict = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getEvictChannel() { return evictChannel; }
        public void setEvictChannel(String evictChannel) { this.evictChannel = evictChannel; }
        public boolean isPublishSelfEvict() { return publishSelfEvict; }
        public void setPublishSelfEvict(boolean publishSelfEvict) { this.publishSelfEvict = publishSelfEvict; }
    }

    /**
     * B1：per-FlowSession 下行缓冲配置。
     * <p>详见 {@code openspec/changes/add-flow-downstream-buffer/design.md} §3 / §6。
     */
    public static class BufferConfig {
        /** 全局开关；off 时退化为 Phase A 行为（不入 buffer / 不重放） */
        private boolean enabled = true;
        /** 单 FlowSession entries 上限 */
        private int capacityEntries = 256;
        /** 单 FlowSession 字节上限 */
        private long capacityBytes = 4L * 1024L * 1024L;
        /** overflow 策略：{@code drop_oldest} | {@code force_detach} */
        private String overflowPolicy = "drop_oldest";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getCapacityEntries() { return capacityEntries; }
        public void setCapacityEntries(int capacityEntries) { this.capacityEntries = capacityEntries; }
        public long getCapacityBytes() { return capacityBytes; }
        public void setCapacityBytes(long capacityBytes) { this.capacityBytes = capacityBytes; }
        public String getOverflowPolicy() { return overflowPolicy; }
        public void setOverflowPolicy(String overflowPolicy) { this.overflowPolicy = overflowPolicy; }
    }

    /**
     * B1：服务端 features 通告配置。
     */
    public static class FeaturesConfig {
        /** 是否向客户端通告支持 gwSeq stamp */
        private boolean advertiseGwSeq = true;
        /** 是否向客户端通告支持 RESUME 重放 */
        private boolean advertiseReplay = true;

        public boolean isAdvertiseGwSeq() { return advertiseGwSeq; }
        public void setAdvertiseGwSeq(boolean advertiseGwSeq) { this.advertiseGwSeq = advertiseGwSeq; }
        public boolean isAdvertiseReplay() { return advertiseReplay; }
        public void setAdvertiseReplay(boolean advertiseReplay) { this.advertiseReplay = advertiseReplay; }
    }
}
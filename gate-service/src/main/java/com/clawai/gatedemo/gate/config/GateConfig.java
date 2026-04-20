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
}
package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "gate")
public class GateConfig {

    private String id = "gate-01";
    private String host = "0.0.0.0";
    private int port = 8888;
    private List<GameInstance> games = new ArrayList<>();
    private Player player = new Player();
    private LoginService loginService = new LoginService();
    private DiscoveryConfig discovery = new DiscoveryConfig();
    private GrpcPoolConfig grpcPool = new GrpcPoolConfig();
    private RedisConfig redis = new RedisConfig();
    private HealthConfig health = new HealthConfig();
    private TlsConfig tls = new TlsConfig();
    private TcpConfig tcp = new TcpConfig();
    private ClusterConfig cluster = new ClusterConfig();

    public static class TcpConfig {
        private boolean enabled = false;
        private int port = 9999;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class ClusterConfig {
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }

    public static class HealthConfig {
        private int port = 8890;
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class TlsConfig {
        private boolean enabled = false;
        private String certPath;
        private String keyPath;
        private String keystorePath;
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

    public static class GameInstance {
        private int id;
        private String host = "localhost";
        private int port = 9090;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Player {
        private int heartbeatInterval = 60;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class LoginService {
        private String host = "localhost";
        private int port = 9081;
        private int heartbeatInterval = 30;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class DiscoveryConfig {
        private boolean enabled = true;
        private int initialLoadTimeout = 5000;
        private long staleThreshold = 120000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getInitialLoadTimeout() { return initialLoadTimeout; }
        public void setInitialLoadTimeout(int initialLoadTimeout) { this.initialLoadTimeout = initialLoadTimeout; }
        public long getStaleThreshold() { return staleThreshold; }
        public void setStaleThreshold(long staleThreshold) { this.staleThreshold = staleThreshold; }
    }

    public static class GrpcPoolConfig {
        private long keepAliveTime = 30;
        private long keepAliveTimeout = 10;
        private boolean keepAliveWithoutCalls = true;
        private long reconnectDelay = 5000;
        private long reconnectMaxDelay = 60000;
        private double reconnectMultiplier = 2.0;
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

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;
        private SentinelConfig sentinel = new SentinelConfig();

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
        public SentinelConfig getSentinel() { return sentinel; }
        public void setSentinel(SentinelConfig sentinel) { this.sentinel = sentinel; }
    }

    public static class SentinelConfig {
        private String master;
        private List<String> nodes = new ArrayList<>();

        public String getMaster() { return master; }
        public void setMaster(String master) { this.master = master; }
        public List<String> getNodes() { return nodes; }
        public void setNodes(List<String> nodes) { this.nodes = nodes != null ? nodes : new ArrayList<>(); }
        /** Accept comma-separated host:port for env var injection (e.g. REDIS_SENTINEL_NODES). */
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
    public List<GameInstance> getGames() { return games; }
    public void setGames(List<GameInstance> games) { this.games = games; }
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

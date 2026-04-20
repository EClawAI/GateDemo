package com.clawai.gatedemo.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定 {@code game.*} 配置：实例标识、监听地址、Redis、状态心跳与注册发现参数，是 Game 与 Login/注册表协作的单一配置源。
 */
@Configuration
@ConfigurationProperties(prefix = "game")
public class GameConfig {

    /** 本 Game 实例标识，常用于解析数值 gameId、Redis 键后缀等。 */
    private String id = "game-1001";
    /** 注册到 Redis 等对外宣告的可达主机名或 IP。 */
    private String host = "localhost";
    /** gRPC 等服务对外监听端口（与注册信息中的 port 一致）。 */
    private int port = 9090;
    private RedisConfig redis = new RedisConfig();
    private StatusConfig status = new StatusConfig();
    private RegistryConfig registry = new RegistryConfig();
    /** SLG 沙盘 / 联盟 / Worker 等与「大地图聚合」相关的开关与路由键。 */
    private SlgConfig slg = new SlgConfig();

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        /** Redis 6+ ACL 用户名；空表示仅密码认证（{@code AUTH password}） */
        private String username = "";
        private String password = "";
        /** Redis 逻辑库编号。 */
        private int database = 0;

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
    }

    public static class StatusConfig {
        /** 向 Redis 同步游戏状态的心跳间隔（毫秒），可由 {@code game.status.heartbeat-interval} 覆盖调度。 */
        private int heartbeatInterval = 30000;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class RegistryConfig {
        /** 是否向 Redis 注册本实例并发送心跳。 */
        private boolean enabled = true;
        /** 注册键的生存时间（秒），需小于或配合心跳续期。 */
        private int ttlSeconds = 60;
        /** 注册续期/心跳任务间隔（毫秒）。 */
        private int heartbeatInterval = 30000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(int ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public RedisConfig getRedis() { return redis; }
    public void setRedis(RedisConfig redis) { this.redis = redis; }
    public StatusConfig getStatus() { return status; }
    public void setStatus(StatusConfig status) { this.status = status; }
    public RegistryConfig getRegistry() { return registry; }
    public void setRegistry(RegistryConfig registry) { this.registry = registry; }
    public SlgConfig getSlg() { return slg; }
    public void setSlg(SlgConfig slg) { this.slg = slg; }

    /**
     * 分服 SLG：沙盘 Actor 实例键为 {@code game.id}（本进程分服标识）与 {@link #gameplayId}（玩法模式）；
     * 持久化层若按 region 分片，可使用 {@link #sandboxPersistenceRegionId} 与城 {@code cityId} 组合。
     */
    public static class SlgConfig {
        /** 玩法模式键，与 {@code game.id} 共同确定「每服 × 每玩法」沙盘逻辑实例。 */
        private String gameplayId = "default";
        /** 沙盘侧 {@link com.clawai.gatedemo.game.persistence.CityWorldStatePersistence} 使用的 region 槽位（单沙盘多城时共用）。 */
        private long sandboxPersistenceRegionId = 0L;

        public String getGameplayId() { return gameplayId; }
        public void setGameplayId(String gameplayId) { this.gameplayId = gameplayId; }
        public long getSandboxPersistenceRegionId() { return sandboxPersistenceRegionId; }
        public void setSandboxPersistenceRegionId(long sandboxPersistenceRegionId) {
            this.sandboxPersistenceRegionId = sandboxPersistenceRegionId;
        }
    }
}

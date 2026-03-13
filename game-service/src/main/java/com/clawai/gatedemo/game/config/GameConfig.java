package com.clawai.gatedemo.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "game")
public class GameConfig {

    private String id = "game-1001";
    private String host = "localhost";
    private int port = 9090;
    private Cache cache = new Cache();
    private RedisConfig redis = new RedisConfig();
    private StatusConfig status = new StatusConfig();
    private RegistryConfig registry = new RegistryConfig();

    public static class Cache {
        private int maxSize = 10000;
        private int expireMinutes = 30;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(int expireMinutes) { this.expireMinutes = expireMinutes; }
    }

    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public int getDatabase() { return database; }
        public void setDatabase(int database) { this.database = database; }
    }

    public static class StatusConfig {
        private int heartbeatInterval = 30000;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    public static class RegistryConfig {
        private boolean enabled = true;
        private int ttlSeconds = 60;
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
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
    public RedisConfig getRedis() { return redis; }
    public void setRedis(RedisConfig redis) { this.redis = redis; }
    public StatusConfig getStatus() { return status; }
    public void setStatus(StatusConfig status) { this.status = status; }
    public RegistryConfig getRegistry() { return registry; }
    public void setRegistry(RegistryConfig registry) { this.registry = registry; }
}

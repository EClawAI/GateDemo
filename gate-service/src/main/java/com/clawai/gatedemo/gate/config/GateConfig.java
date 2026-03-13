package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
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
    private RedisConfig redis = new RedisConfig();

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
    public RedisConfig getRedis() { return redis; }
    public void setRedis(RedisConfig redis) { this.redis = redis; }
}

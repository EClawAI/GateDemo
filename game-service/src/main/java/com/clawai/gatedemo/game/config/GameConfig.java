package com.clawai.gatedemo.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "game")
public class GameConfig {

    private String id = "game-1001";
    private Cache cache = new Cache();
    private LoginService loginService = new LoginService();

    public static class Cache {
        private int maxSize = 10000;
        private int expireMinutes = 30;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(int expireMinutes) { this.expireMinutes = expireMinutes; }
    }

    public static class LoginService {
        private String host = "localhost";
        private int port = 8081;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
    public LoginService getLoginService() { return loginService; }
    public void setLoginService(LoginService loginService) { this.loginService = loginService; }
}
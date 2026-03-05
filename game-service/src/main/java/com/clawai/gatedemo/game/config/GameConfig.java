package com.clawai.gatedemo.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "game")
public class GameConfig {

    private String id = "game-1001";
    private Cache cache = new Cache();

    public static class Cache {
        private int maxSize = 10000;
        private int expireMinutes = 30;

        public int getMaxSize() { return maxSize; }
        public void setMaxSize(int maxSize) { this.maxSize = maxSize; }
        public int getExpireMinutes() { return expireMinutes; }
        public void setExpireMinutes(int expireMinutes) { this.expireMinutes = expireMinutes; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Cache getCache() { return cache; }
    public void setCache(Cache cache) { this.cache = cache; }
}
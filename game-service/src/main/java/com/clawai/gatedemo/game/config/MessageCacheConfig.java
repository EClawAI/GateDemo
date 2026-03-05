package com.clawai.gatedemo.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class MessageCacheConfig {

    private final GameConfig gameConfig;

    public MessageCacheConfig(GameConfig gameConfig) {
        this.gameConfig = gameConfig;
    }

    /**
     * 内存消息缓存：gateId -> playerId -> pending messages
     * 用于缓存从 Gate 发送过来的消息，等待玩家上线处理
     */
    @Bean
    public ConcurrentMap<String, ConcurrentMap<Long, Queue<Map<String, Object>>>> messageCache() {
        return new ConcurrentHashMap<>();
    }
}

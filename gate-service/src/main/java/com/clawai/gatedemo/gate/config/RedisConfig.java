package com.clawai.gatedemo.gate.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {

    private final GateConfig gateConfig;

    public RedisConfig(GateConfig gateConfig) {
        this.gateConfig = gateConfig;
    }

    @Bean
    public RedisClient redisClient() {
        return RedisClient.create(
            RedisURI.builder()
                .withHost(gateConfig.getRedis().getHost())
                .withPort(gateConfig.getRedis().getPort())
                .build()
        );
    }

    @Bean
    public StatefulRedisConnection<String, String> redisConnection(RedisClient redisClient) {
        return redisClient.connect();
    }

    @Bean
    public RedisReactiveCommands<String, String> redisCommands(StatefulRedisConnection<String, String> connection) {
        return connection.reactive();
    }
}
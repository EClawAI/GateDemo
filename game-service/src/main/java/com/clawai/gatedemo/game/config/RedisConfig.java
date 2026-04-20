package com.clawai.gatedemo.game.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 为 Game 服务提供独立 Redis 连接与 {@link RedisTemplate}，支撑游戏注册、状态同步等跨服务共享数据。
 */
@Configuration
public class RedisConfig {

    private final GameConfig gameConfig;

    /** @param gameConfig 读取 {@code game.redis.*} 连接参数 */
    public RedisConfig(GameConfig gameConfig) {
        this.gameConfig = gameConfig;
    }

    /**
     * 基于 {@link GameConfig#getRedis()} 构建单机 Lettuce 连接工厂；密码非空时启用认证。
     *
     * @return 可注入到 {@link RedisTemplate} 的连接工厂
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(gameConfig.getRedis().getHost());
        config.setPort(gameConfig.getRedis().getPort());
        config.setDatabase(gameConfig.getRedis().getDatabase());
        if (gameConfig.getRedis().getUsername() != null && !gameConfig.getRedis().getUsername().isEmpty()) {
            config.setUsername(gameConfig.getRedis().getUsername());
        }
        if (gameConfig.getRedis().getPassword() != null && !gameConfig.getRedis().getPassword().isEmpty()) {
            config.setPassword(gameConfig.getRedis().getPassword());
        }
        return new LettuceConnectionFactory(config);
    }

    /**
     * 纯字符串 KV，供 {@code game:status:*} 等与 Login 共读的键，避免 Jackson 包装导致对端解析失败。
     */
    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * String 键 + JSON 值（含 Hash）的通用模板，供注册表、状态键等读写。
     *
     * @param connectionFactory Redis 连接
     * @return 已 {@code afterPropertiesSet} 的模板
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(jsonSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(jsonSerializer);
        
        template.afterPropertiesSet();
        return template;
    }
}

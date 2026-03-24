package com.clawai.gatedemo.gate.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

/**
 * Redis 自动配置：支持单机与 Sentinel；当配置了 {@code gate.redis.sentinel.master} 时走哨兵，否则为单机直连。
 */
@Configuration
public class RedisConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(RedisConfiguration.class);

    private final GateConfig gateConfig;

    /**
     * @param gateConfig 读取 {@code gate.redis} 与哨兵节点列表
     */
    public RedisConfiguration(GateConfig gateConfig) {
        this.gateConfig = gateConfig;
    }

    /**
     * 根据配置创建 Lettuce 连接工厂；哨兵模式下密码与 database 会应用到 Sentinel 配置。
     *
     * @return 可用于 {@link RedisTemplate}、监听容器等的连接工厂
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        GateConfig.RedisConfig redis = gateConfig.getRedis();
        GateConfig.SentinelConfig sentinel = redis.getSentinel();

        if (sentinel != null && sentinel.getMaster() != null && !sentinel.getMaster().isEmpty()) {
            RedisSentinelConfiguration sentinelConfig = createSentinelConfig(redis, sentinel);
            logger.info("Using Redis Sentinel mode, master={}", sentinel.getMaster());
            return new LettuceConnectionFactory(sentinelConfig);
        } else {
            RedisStandaloneConfiguration standaloneConfig = new RedisStandaloneConfiguration();
            standaloneConfig.setHostName(redis.getHost());
            standaloneConfig.setPort(redis.getPort());
            standaloneConfig.setDatabase(redis.getDatabase());
            if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
                standaloneConfig.setPassword(redis.getPassword());
            }
            logger.info("Using Redis standalone mode, host={}, port={}", redis.getHost(), redis.getPort());
            return new LettuceConnectionFactory(standaloneConfig);
        }
    }

    private RedisSentinelConfiguration createSentinelConfig(GateConfig.RedisConfig redis,
                                                           GateConfig.SentinelConfig sentinel) {
        RedisSentinelConfiguration config = new RedisSentinelConfiguration()
                .master(sentinel.getMaster());

        List<String> nodes = sentinel.getNodes();
        if (nodes != null) {
            for (String node : nodes) {
                String[] parts = node.trim().split(":");
                String host = parts[0].trim();
                int port = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 26379;
                config.sentinel(host, port);
            }
        }

        config.setDatabase(redis.getDatabase());
        if (redis.getPassword() != null && !redis.getPassword().isEmpty()) {
            config.setPassword(redis.getPassword());
        }
        return config;
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        return template;
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}

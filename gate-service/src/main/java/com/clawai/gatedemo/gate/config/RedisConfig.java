package com.clawai.gatedemo.gate.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis配置类 - 配置Redis连接和操作模板
 *
 * 设计原理：
 * 本项目使用Redis做两件事：
 * 1. 离线消息存储（Redis Stream）
 * 2. 消息队列缓冲
 *
 * 为什么选择Lettuce？
 * - Spring Data Redis默认使用的Redis客户端
 * - 基于Netty，异步非阻塞
 * - 支持连接池
 * - 支持哨兵和集群模式
 *
 * 配置项：
 * - host: Redis服务器地址
 * - port: Redis服务器端口
 * - password: Redis密码（可选）
 *
 * RedisTemplate：
 * - Spring Data Redis的核心类
 * - 封装了Redis的各种操作
 * - 支持泛型，自动序列化/反序列化
 *
 * 序列化器说明：
 * - StringRedisSerializer: Key使用字符串
 * - GenericJackson2JsonRedisSerializer: Value使用JSON
 *   - 优点：自动转换为Java对象
 *   - 缺点：性能略低于专用序列化器
 *
 * 使用示例：
 * <pre>
 * {@code
 * @Autowired
 * private RedisTemplate<String, Object> redisTemplate;
 *
 * // 存储
 * redisTemplate.opsForValue().set("key", "value");
 *
 * // 获取
 * String value = (String) redisTemplate.opsForValue().get("key");
 *
 * // 操作Stream
 * redisTemplate.opsForStream().add(...);
 * }
 * </pre>
 *
 * 配置来源：
 * - 默认值：application.yml中的配置
 * - 环境变量：可以通过环境变量覆盖
 * - 例如：REDIS_HOST=192.168.1.100 mvn spring-boot:run
 */
@Configuration
public class RedisConfig {

    /** Redis服务器地址，默认localhost */
    @Value("${gate.redis.host:localhost}")
    private String redisHost;

    /** Redis服务器端口，默认6379 */
    @Value("${gate.redis.port:6379}")
    private int redisPort;

    /** Redis密码，默认空（无密码） */
    @Value("${gate.redis.password:}")
    private String redisPassword;

    /**
     * 创建Redis连接工厂
     *
     * RedisConnectionFactory是Spring Data Redis的核心接口：
     * - 管理与Redis服务器的连接
     * - 支持连接池
     *
     * RedisStandaloneConfiguration：
     * - 单机模式的配置
     * - 支持哨兵和集群使用对应配置类
     *
     * @return Redis连接工厂
     */
    @Bean
    public RedisConnectionFactory redisConnectionFactory() {
        // 创建单机配置
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration();
        config.setHostName(redisHost);
        config.setPort(redisPort);
        
        // 如果配置了密码，则设置密码
        if (redisPassword != null && !redisPassword.isEmpty()) {
            config.setPassword(redisPassword);
        }
        
        // 使用Lettuce作为Redis客户端
        return new LettuceConnectionFactory(config);
    }

    /**
     * 创建RedisTemplate
     *
     * RedisTemplate是操作Redis的核心类：
     * - 封装了Redis的各种操作命令
     * - 支持自动序列化/反序列化
     *
     * 序列化配置：
     * - Key: 字符串序列化
     * - Value: JSON序列化（GenericJackson2JsonRedisSerializer）
     * - Hash Key: 字符串序列化
     * - Hash Value: JSON序列化
     *
     * 为什么这样配置？
     * - Key用字符串：便于调试和查看
     * - Value用JSON：灵活，兼容性好
     *
     * @param connectionFactory Redis连接工厂
     * @return RedisTemplate实例
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        
        // 设置连接工厂
        template.setConnectionFactory(connectionFactory);
        
        // Key序列化器：字符串
        template.setKeySerializer(new StringRedisSerializer());
        
        // Value序列化器：JSON（支持泛型）
        template.setValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        // Hash Key序列化器：字符串
        template.setHashKeySerializer(new StringRedisSerializer());
        
        // Hash Value序列化器：JSON
        template.setHashValueSerializer(new GenericJackson2JsonRedisSerializer());
        
        return template;
    }
}

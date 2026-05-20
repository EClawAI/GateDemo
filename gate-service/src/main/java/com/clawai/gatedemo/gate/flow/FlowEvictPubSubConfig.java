package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * B2：注册 {@link RedisMessageListenerContainer} 并将 {@link FlowEvictListener} 订阅到
 * {@link GateConfig.CrossConfig#getEvictChannel()}。
 *
 * <p>只有 {@code gate.flow.cross.enabled=true}（默认 true）且存在 {@link RedisConnectionFactory}
 * 才装配；离线 / 单测环境无 Redis 时静默不订阅。
 */
@Configuration
@ConditionalOnProperty(name = "gate.flow.cross.enabled", havingValue = "true", matchIfMissing = true)
public class FlowEvictPubSubConfig {

    private static final Logger logger = LoggerFactory.getLogger(FlowEvictPubSubConfig.class);

    @Bean
    public RedisMessageListenerContainer flowEvictListenerContainer(
            @Autowired(required = false) RedisConnectionFactory connectionFactory,
            FlowEvictListener listener,
            GateConfig gateConfig) {
        if (connectionFactory == null) {
            logger.warn("FlowEvictPubSubConfig: RedisConnectionFactory missing, skipping subscription");
            RedisMessageListenerContainer empty = new RedisMessageListenerContainer();
            return empty;
        }
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        String channel = gateConfig.getFlow().getCross().getEvictChannel();
        container.addMessageListener(listener, new PatternTopic(channel));
        logger.info("FlowEvictPubSubConfig subscribed channel={} gateId={}",
                channel, gateConfig.getId());
        return container;
    }
}

package com.clawai.gatedemo.game.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 提供应用级 {@link ObjectMapper} Bean，供消息体 JSON 解析、gRPC 与 Redis 序列化等注入使用。
 */
@Configuration
public class ObjectMapperConfig {

    /**
     * 默认配置的 Jackson 实例；需定制模块或日期格式时可在此集中调整。
     *
     * @return 单例 ObjectMapper
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

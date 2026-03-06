package com.clawai.gatedemo.game.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ObjectMapper 配置
 * 
 * 提供 JSON 序列化工具 Bean
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@Configuration
public class ObjectMapperConfig {

    /**
     * 创建 ObjectMapper Bean
     * 
     * @return 配置好的 ObjectMapper 实例
     */
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}

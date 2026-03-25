package com.clawai.gatedemo.game.config;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.core.message.MessageHandlerScanner;
import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册 core 消息框架的 Spring Bean 并在启动时加载消息路由表。
 */
@Configuration
public class MessageHandlerConfig {

    @PostConstruct
    public void loadRouteRegistry() {
        MessageRouteRegistry.loadFromJson("message_registry.json");
    }

    @Bean
    public MessageHandlerRegistry messageHandlerRegistry() {
        return new MessageHandlerRegistry();
    }

    @Bean
    public MessageHandlerScanner messageHandlerScanner(MessageHandlerRegistry registry) {
        return new MessageHandlerScanner(registry);
    }
}

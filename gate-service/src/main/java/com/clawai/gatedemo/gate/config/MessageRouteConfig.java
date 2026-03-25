package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 启动时从 message_registry.json 加载消息路由表到 {@link MessageRouteRegistry}。
 * JSON 文件由 tools/gen_proto.sh 脚本生成。
 */
@Configuration
public class MessageRouteConfig {

    @PostConstruct
    public void init() {
        MessageRouteRegistry.loadFromJson("message_registry.json");
    }
}

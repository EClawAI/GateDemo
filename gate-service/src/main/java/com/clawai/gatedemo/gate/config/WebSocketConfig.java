package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.gate.handler.GateWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final GateWebSocketHandler gateWebSocketHandler;

    public WebSocketConfig(GateWebSocketHandler gateWebSocketHandler) {
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(gateWebSocketHandler, "/ws")
            .setAllowedOrigins("*");
    }
}
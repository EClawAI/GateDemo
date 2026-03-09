package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.gate.handler.GateNettyWebSocketHandler;
import com.clawai.gatedemo.gate.ws.NettyWebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * WebSocket服务器配置 - Spring配置类，负责组装和启动服务
 *
 * 职责：
 * 1. 注入依赖（GateConfig、Handler）
 * 2. 创建服务实例
 * 3. 调用服务启动/停止
 *
 * 架构说明：
 * - 本类：配置类，Spring Bean管理
 * - NettyWebSocketServer：服务类，纯业务逻辑
 *
 * 这样做的好处：
 * - 单一职责：配置和服务分离
 * - 易于测试：服务类可以单独测试
 * - 清晰结构：config包只管配置，ws包管服务
 */
@Component
public class NettyWebSocketServerConfig {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServerConfig.class);

    /** Gate配置 */
    private final GateConfig gateConfig;

    /** 玩家连接处理器 */
    private final GateNettyWebSocketHandler gateWebSocketHandler;

    /** WebSocket服务器实例 */
    private NettyWebSocketServer webSocketServer;

    /**
     * 构造函数 - Spring自动注入依赖
     */
    public NettyWebSocketServerConfig(GateConfig gateConfig, GateNettyWebSocketHandler gateWebSocketHandler) {
        this.gateConfig = gateConfig;
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    /**
     * 启动服务器
     */
    @PostConstruct
    public void start() {
        logger.info("=== 启动 WebSocket 服务器配置 ===");
        
        // 创建服务实例，注入GateConfig
        webSocketServer = new NettyWebSocketServer(gateConfig, gateWebSocketHandler);
        
        // 启动服务
        webSocketServer.start();
    }

    /**
     * 停止服务器
     */
    @PreDestroy
    public void stop() {
        if (webSocketServer != null) {
            webSocketServer.stop();
        }
    }
}

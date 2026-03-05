package com.clawai.gatedemo.gate;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.config.NettyWebSocketServer;
import com.clawai.gatedemo.gate.handler.GateNettyWebSocketHandler;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gate 服务启动类
 * 
 * 架构说明：
 * - 使用 Spring Boot 仅用于配置加载和生命周期管理
 * - Netty 组件纯 Java 方式组装，不依赖 Spring 注解
 * - 最小化 Spring 依赖，最大化 Netty 控制
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@SpringBootApplication
public class GateServiceApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GateServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(GateServiceApplication.class, args);
    }

    @Override
    public void run(String... args) {
        logger.info("=== Gate Service 启动 ===");

        // 1. 创建配置对象（从 application.yml 读取）
        GateConfig gateConfig = new GateConfig();
        // 实际项目中可以通过 @Value 或 Environment 读取配置

        // 2. 创建 JSON 工具
        ObjectMapper objectMapper = new ObjectMapper();

        // 3. 创建玩家服务（纯 Java 对象）
        PlayerService playerService = new PlayerService(gateConfig, objectMapper);

        // 4. 创建 WebSocket 处理器（纯 Java 对象）
        GateNettyWebSocketHandler handler = new GateNettyWebSocketHandler(playerService, objectMapper);

        // 5. 创建 Netty WebSocket 服务器（纯 Java 对象）
        NettyWebSocketServer server = new NettyWebSocketServer(gateConfig.getPort(), handler);

        // 6. 启动服务器
        server.start();

        // 7. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止服务器...");
            server.stop();
        }));

        logger.info("=== Gate Service 启动完成 ===");
    }
}
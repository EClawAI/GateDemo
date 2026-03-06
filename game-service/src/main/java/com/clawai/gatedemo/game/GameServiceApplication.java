package com.clawai.gatedemo.game;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;

/**
 * Game 服务启动类（纯 gRPC，无 HTTP）
 * 
 * 架构说明：
 * - 排除 Web MVC 自动配置，不启动 HTTP 服务器
 * - 仅通过 gRPC 提供服务（默认端口 9090）
 * - 适用于纯内部服务调用场景
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@SpringBootApplication(exclude = {
    WebMvcAutoConfiguration.class  // 排除 Web MVC 自动配置
})
public class GameServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(GameServiceApplication.class, args);
    }
}
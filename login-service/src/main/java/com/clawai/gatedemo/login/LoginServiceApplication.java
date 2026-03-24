package com.clawai.gatedemo.login;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 登录服务 Spring Boot 入口，承载玩家鉴权、JWT 签发、游戏路由与网关协作等 HTTP API。
 */
@SpringBootApplication
@EnableScheduling
public class LoginServiceApplication {

    /**
     * 启动 Spring 应用上下文并运行登录服务。
     *
     * @param args 命令行参数，透传给 Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(LoginServiceApplication.class, args);
    }
}

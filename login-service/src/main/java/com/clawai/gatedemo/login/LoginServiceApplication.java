package com.clawai.gatedemo.login;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 登录服务 Spring Boot 入口，承载玩家鉴权、JWT 签发、游戏路由与网关协作等 HTTP API。
 */
@SpringBootApplication
public class LoginServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(LoginServiceApplication.class, args);
    }
}

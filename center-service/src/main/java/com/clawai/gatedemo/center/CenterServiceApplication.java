package com.clawai.gatedemo.center;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 中心服务 Spring Boot 入口，对外提供客户端拉取的版本、SDK、登录入口与公告等配置类 HTTP API。
 */
@SpringBootApplication
public class CenterServiceApplication {
    
    public static void main(String[] args) {
        SpringApplication.run(CenterServiceApplication.class, args);
    }
}

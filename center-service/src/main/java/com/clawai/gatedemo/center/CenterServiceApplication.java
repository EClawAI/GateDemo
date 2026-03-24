package com.clawai.gatedemo.center;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 中心服务 Spring Boot 入口，对外提供客户端拉取的版本、SDK、登录入口与公告等配置类 HTTP API。
 */
@SpringBootApplication
public class CenterServiceApplication {

    /**
     * 启动 Spring 应用上下文并运行中心配置服务。
     *
     * @param args 命令行参数，透传给 Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(CenterServiceApplication.class, args);
    }
}

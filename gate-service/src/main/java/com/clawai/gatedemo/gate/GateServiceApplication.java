package com.clawai.gatedemo.gate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 网关服务 Spring Boot 启动入口；开启定时任务以支撑发现、巡检等后台调度。
 */
@SpringBootApplication
@EnableScheduling
public class GateServiceApplication {

    /**
     * 启动网关 Spring 应用，注册 Bean 与调度器（含 {@link org.springframework.scheduling.annotation.EnableScheduling}）。
     *
     * @param args 命令行参数，原样传给 {@link SpringApplication#run}
     */
    public static void main(String[] args) {
        SpringApplication.run(GateServiceApplication.class, args);
    }
}
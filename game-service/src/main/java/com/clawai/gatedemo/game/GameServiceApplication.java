package com.clawai.gatedemo.game;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Game 服务启动类（纯 gRPC，无 HTTP）
 * 
 * 架构说明：
 * - 排除 Web MVC 自动配置，不启动 HTTP 服务器
 * - 仅通过 gRPC 提供服务（默认端口 9090）
 * - 使用 CountDownLatch 保持应用运行
 * - 适用于纯内部服务调用场景
 * 
 * @author clawAI
 * @since 2026-03-06
 */
@SpringBootApplication(exclude = {
    WebMvcAutoConfiguration.class  // 排除 Web MVC 自动配置
})
public class GameServiceApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GameServiceApplication.class);

    // 用于保持应用运行的锁
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static final AtomicBoolean running = new AtomicBoolean(true);

    public static void main(String[] args) {
        SpringApplication.run(GameServiceApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        logger.info("===========================================");
        logger.info("✅ Game 服务已启动（纯 gRPC 模式）");
        logger.info("按 Ctrl+C 停止服务");
        logger.info("===========================================");

        // 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止服务...");
            running.set(false);
            latch.countDown();  // 释放锁，允许主线程退出
        }));

        // 保持主线程运行
        try {
            latch.await();  // 阻塞直到关闭信号
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("主线程被中断");
        }

        logger.info("Game 服务已停止");
    }
}
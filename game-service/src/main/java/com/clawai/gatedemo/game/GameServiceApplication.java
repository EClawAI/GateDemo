package com.clawai.gatedemo.game;

import com.clawai.gatedemo.game.service.GameStatus;
import com.clawai.gatedemo.game.service.GameStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

@SpringBootApplication(exclude = {
    WebMvcAutoConfiguration.class
})
@EnableScheduling
public class GameServiceApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GameServiceApplication.class);

    private static final CountDownLatch latch = new CountDownLatch(1);
    private static final AtomicBoolean running = new AtomicBoolean(true);

    private final GameStatusService gameStatusService;

    public GameServiceApplication(GameStatusService gameStatusService) {
        this.gameStatusService = gameStatusService;
    }

    public static void main(String[] args) {
        SpringApplication.run(GameServiceApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        gameStatusService.setStatus(GameStatus.STARTED_CAN_LOGIN);

        logger.info("===========================================");
        logger.info("✅ Game 服务已启动（纯 gRPC 模式）");
        logger.info("游戏状态: 可以登录");
        logger.info("按 Ctrl+C 停止服务");
        logger.info("===========================================");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("收到关闭信号，正在停止服务...");
            running.set(false);
            latch.countDown();
        }));

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("主线程被中断");
        }

        logger.info("Game 服务已停止");
    }
}

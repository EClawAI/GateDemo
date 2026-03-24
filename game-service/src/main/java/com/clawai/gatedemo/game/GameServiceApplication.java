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

/**
 * Game 微服务入口：无 Web MVC、以 gRPC/调度为主进程；启动后将可登录状态交给 {@link GameStatusService}，供 Login 与网关做登录门禁。
 */
@SpringBootApplication(exclude = {
    WebMvcAutoConfiguration.class
})
@EnableScheduling
public class GameServiceApplication implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(GameServiceApplication.class);

    /** 阻塞主线程直至收到关闭信号，与 shutdown hook 配合实现“常驻进程”。 */
    private static final CountDownLatch latch = new CountDownLatch(1);
    /** 预留的运行标志位，供关闭钩子等读取（当前与 latch 联动停止流程）。 */
    private static final AtomicBoolean running = new AtomicBoolean(true);

    private final GameStatusService gameStatusService;

    public GameServiceApplication(GameStatusService gameStatusService) {
        this.gameStatusService = gameStatusService;
    }

    /**
     * 启动 Spring 容器并执行 {@link CommandLineRunner} 逻辑。
     *
     * @param args 命令行参数，透传给 Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(GameServiceApplication.class, args);
    }

    /**
     * 容器就绪后将会话门禁设为可登录，注册 JVM 关闭钩子在退出前释放 latch，主线程 await 保持进程不退出。
     *
     * @param args 未使用
     * @throws Exception 未在此方法中主动抛出；latch 等待可被中断并恢复中断标志
     */
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

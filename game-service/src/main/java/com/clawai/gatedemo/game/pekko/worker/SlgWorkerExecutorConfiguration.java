package com.clawai.gatedemo.game.pekko.worker;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 与 Pekko {@code default-dispatcher} 隔离的线程池，供无状态 Worker（{@link FakeCombatCalculator} 等）使用；
 * HOCON 中另有 {@code pekko.actor.game-slg-worker-dispatcher} 可供需要绑定 Pekko dispatcher 的场景选用。
 */
@Configuration
public class SlgWorkerExecutorConfiguration {

    public static final String GAME_SLG_WORKER_EXECUTOR = "gameSlgWorkerExecutor";

    @Bean(name = GAME_SLG_WORKER_EXECUTOR)
    public Executor gameSlgWorkerExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(8);
        ex.setQueueCapacity(512);
        ex.setThreadNamePrefix("game-slg-worker-");
        ex.initialize();
        return ex;
    }
}

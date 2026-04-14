package com.clawai.gatedemo.game.pekko;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * 嵌入名为 {@code game} 的单个 Pekko Typed {@link ActorSystem}，守护 Actor 为 {@link SpawnProtocol}，
 * 供业务通过 Ask 等方式 spawn 子 Actor（如流入口）。
 * <p>
 * 关闭：Spring 容器关闭时执行 {@link #destroy()}，调用 {@link ActorSystem#terminate()} 并等待结束（带超时）。
 * {@link com.clawai.gatedemo.game.GameServiceApplication} 另注册 JVM {@link Runtime#addShutdownHook shutdown hook}
 * 仅释放 {@link java.util.concurrent.CountDownLatch}；Pekko 有序停机主要由 Spring {@code DisposableBean}
 * 在上下文停止时触发（如 Spring Boot 处理 SIGTERM）。
 */
@Configuration
public class PekkoActorSystemConfiguration implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(PekkoActorSystemConfiguration.class);

    private static final Duration TERMINATE_TIMEOUT = Duration.ofSeconds(30);

    private volatile ActorSystem<SpawnProtocol.Command> gameActorSystem;

    @Bean
    public ActorSystem<SpawnProtocol.Command> gameActorSystem() {
        Config config = ConfigFactory.load();
        ActorSystem<SpawnProtocol.Command> system =
                ActorSystem.create(SpawnProtocol.create(), "game", config);
        this.gameActorSystem = system;
        return system;
    }

    @Override
    public void destroy() {
        ActorSystem<SpawnProtocol.Command> system = gameActorSystem;
        if (system == null) {
            return;
        }
        logger.info("Terminating Pekko ActorSystem [{}]...", system.name());
        system.terminate();
        try {
            system.getWhenTerminated().toCompletableFuture().get(
                    TERMINATE_TIMEOUT.getSeconds(), java.util.concurrent.TimeUnit.SECONDS);
            logger.info("Pekko ActorSystem [{}] terminated", system.name());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for ActorSystem termination", e);
        } catch (ExecutionException e) {
            logger.warn("ActorSystem termination completed with failure", e.getCause());
        } catch (TimeoutException e) {
            logger.warn("Timed out after {} waiting for ActorSystem termination", TERMINATE_TIMEOUT, e);
        }
    }
}

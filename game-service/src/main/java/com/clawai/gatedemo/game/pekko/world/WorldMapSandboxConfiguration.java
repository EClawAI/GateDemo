package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.config.GameConfig;
import com.clawai.gatedemo.game.persistence.CityWorldStatePersistence;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatCalculator;
import com.clawai.gatedemo.game.pekko.worker.SlgWorkerExecutorConfiguration;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/** 经 {@link org.apache.pekko.actor.typed.SpawnProtocol} 注册大地图沙盘根 Actor（每服 × 每玩法单实例）。 */
@Configuration
public class WorldMapSandboxConfiguration {

    @Bean
    public ActorRef<WorldMapSandboxBehavior.Command> worldMapSandbox(
            ActorSystem<SpawnProtocol.Command> gameActorSystem,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStatePersistence,
            GameConfig gameConfig,
            FakeCombatCalculator fakeCombatCalculator,
            @Qualifier(SlgWorkerExecutorConfiguration.GAME_SLG_WORKER_EXECUTOR) Executor workerExecutor) {
        CompletionStage<ActorRef<WorldMapSandboxBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        WorldMapSandboxBehavior.create(
                                                gameConfig.getSlg().getGameplayId(),
                                                gameConfig.getSlg().getSandboxPersistenceRegionId(),
                                                playerSessionRegistry,
                                                cityWorldStatePersistence,
                                                fakeCombatCalculator,
                                                workerExecutor),
                                        "world-map-sandbox",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

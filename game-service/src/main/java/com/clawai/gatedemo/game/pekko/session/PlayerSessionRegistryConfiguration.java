package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * 将 {@link PlayerSessionRegistryBehavior} 注册为 Bean，经 {@link org.apache.pekko.actor.typed.SpawnProtocol}
 * 在 {@code game} ActorSystem 中以固定名称 spawn。
 */
@Configuration
public class PlayerSessionRegistryConfiguration {

    @Bean
    public ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry(
            ActorSystem<SpawnProtocol.Command> gameActorSystem,
            GameMessageDispatcher dispatcher,
            PlayerPlunderLedger playerPlunderLedger) {
        CompletionStage<ActorRef<PlayerSessionRegistryBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        PlayerSessionRegistryBehavior.create(dispatcher, playerPlunderLedger),
                                        "player-session-registry",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

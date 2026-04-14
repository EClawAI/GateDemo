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

@Configuration
public class PlayerSessionRegistryConfiguration {

    @Bean
    public ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry(
            ActorSystem<SpawnProtocol.Command> gameActorSystem, GameMessageDispatcher dispatcher) {
        CompletionStage<ActorRef<PlayerSessionRegistryBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        PlayerSessionRegistryBehavior.create(dispatcher),
                                        "player-session-registry",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
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
public class WorldRegistryConfiguration {

    @Bean
    public ActorRef<WorldRegistryBehavior.Command> worldRegistry(
            ActorSystem<SpawnProtocol.Command> gameActorSystem,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry) {
        CompletionStage<ActorRef<WorldRegistryBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        WorldRegistryBehavior.create(playerSessionRegistry),
                                        "world-registry",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

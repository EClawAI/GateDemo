package com.clawai.gatedemo.game.pekko.world;

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
    public ActorRef<WorldRegistryBehavior.Command> worldRegistry(ActorSystem<SpawnProtocol.Command> gameActorSystem) {
        CompletionStage<ActorRef<WorldRegistryBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        WorldRegistryBehavior.create(),
                                        "world-registry",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

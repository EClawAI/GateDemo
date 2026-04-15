package com.clawai.gatedemo.game.pekko.alliance;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.ActorSystem;
import org.apache.pekko.actor.typed.Props;
import org.apache.pekko.actor.typed.SpawnProtocol;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/** 经 {@link SpawnProtocol} 注册联盟注册表 Actor。 */
@Configuration
public class AllianceRegistryConfiguration {

    @Bean
    public ActorRef<AllianceRegistryBehavior.Command> allianceRegistry(
            ActorSystem<SpawnProtocol.Command> gameActorSystem) {
        CompletionStage<ActorRef<AllianceRegistryBehavior.Command>> started =
                AskPattern.ask(
                        gameActorSystem,
                        replyTo ->
                                new SpawnProtocol.Spawn<>(
                                        AllianceRegistryBehavior.create(),
                                        "alliance-registry",
                                        Props.empty(),
                                        replyTo),
                        Duration.ofSeconds(10),
                        gameActorSystem.scheduler());
        return started.toCompletableFuture().join();
    }
}

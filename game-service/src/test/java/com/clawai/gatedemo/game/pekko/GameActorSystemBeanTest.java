package com.clawai.gatedemo.game.pekko;

import org.apache.pekko.actor.typed.ActorSystem;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(PekkoActorSystemConfiguration.class)
class GameActorSystemBeanTest {

    @Autowired
    private ActorSystem<Void> gameActorSystem;

    @Test
    void exposesSingleGameActorSystemBean() {
        assertThat(gameActorSystem).isNotNull();
        assertThat(gameActorSystem.name()).isEqualTo("game");
    }
}

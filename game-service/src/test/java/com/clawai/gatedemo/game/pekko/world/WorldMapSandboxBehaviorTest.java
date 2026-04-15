package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.pekko.worker.DefaultFakeCombatCalculator;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatCalculator;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

class WorldMapSandboxBehaviorTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void sandboxPing_repliesWithoutMongo() {
        Executor exec = ForkJoinPool.commonPool();
        FakeCombatCalculator calc = new DefaultFakeCombatCalculator();
        ActorRef<WorldMapSandboxBehavior.Command> sandbox =
                testKit.spawn(
                        WorldMapSandboxBehavior.create("test", 0L, null, null, calc, exec), "sandbox-ping");
        TestProbe<Integer> probe = testKit.createTestProbe();
        sandbox.tell(new WorldMapSandboxBehavior.SandboxPing(probe.ref()));
        assertThat(probe.expectMessage(1)).isEqualTo(1);
    }
}

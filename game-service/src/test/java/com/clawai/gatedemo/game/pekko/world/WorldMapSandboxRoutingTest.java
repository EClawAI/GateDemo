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

/** 单沙盘邮箱内对同一 {@code cityId} 的 ping 仍按投递顺序处理。 */
class WorldMapSandboxRoutingTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void sandbox_sameCity_serialPingOrder() {
        Executor exec = ForkJoinPool.commonPool();
        FakeCombatCalculator calc = new DefaultFakeCombatCalculator();
        ActorRef<WorldMapSandboxBehavior.Command> sandbox =
                testKit.spawn(
                        WorldMapSandboxBehavior.create("route-test", 0L, null, null, calc, exec), "sandbox-route");
        TestProbe<Integer> probe = testKit.createTestProbe();
        long cityId = 42L;
        sandbox.tell(new WorldMapSandboxBehavior.CityPingSeq(1, cityId, probe.ref()));
        sandbox.tell(new WorldMapSandboxBehavior.CityPingSeq(2, cityId, probe.ref()));
        sandbox.tell(new WorldMapSandboxBehavior.CityPingSeq(3, cityId, probe.ref()));
        probe.expectMessage(1);
        probe.expectMessage(2);
        probe.expectMessage(3);
    }
}

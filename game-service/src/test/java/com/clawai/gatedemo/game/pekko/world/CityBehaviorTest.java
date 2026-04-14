package com.clawai.gatedemo.game.pekko.world;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CityBehaviorTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void sameCity_serialPing_repliesInOrder() {
        TestProbe<Integer> probe = testKit.createTestProbe();
        ActorRef<CityBehavior.CityCommand> city =
                testKit.spawn(CityBehavior.create(10L, 100L), "city");

        city.tell(new CityBehavior.CityPingSeq(1, 100L, probe.ref()));
        city.tell(new CityBehavior.CityPingSeq(2, 100L, probe.ref()));
        city.tell(new CityBehavior.CityPingSeq(3, 100L, probe.ref()));

        probe.expectMessage(1);
        probe.expectMessage(2);
        probe.expectMessage(3);
    }

    @Test
    void wrongTargetCityId_doesNotRunAcceptedHook() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        ActorRef<CityBehavior.CityCommand> city =
                testKit.spawn(CityBehavior.create(10L, 100L, accepted::incrementAndGet), "city2");

        city.tell(new CityBehavior.CityEnvelope(200L, 1L, "atk"));
        assertThat(accepted.get()).isZero();

        city.tell(new CityBehavior.CityEnvelope(100L, 2L, "atk"));
        long deadline = System.currentTimeMillis() + 5000;
        while (accepted.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        assertThat(accepted.get()).isEqualTo(1);
    }
}

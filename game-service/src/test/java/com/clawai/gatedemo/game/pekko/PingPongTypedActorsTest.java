package com.clawai.gatedemo.game.pekko;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.javadsl.Behaviors;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Minimal parent → child {@code tell} path (no Spring), for roadmap stage-1 acceptance.
 */
class PingPongTypedActorsTest {

    @Test
    void parentForwardsToChildAndChildSignalsProbe() {
        ActorTestKit testKit = ActorTestKit.create();
        try {
            TestProbe<String> probe = testKit.createTestProbe();
            ActorRef<String> parent = testKit.spawn(
                    Behaviors.setup(ctx -> {
                        ActorRef<String> child = ctx.spawn(
                                Behaviors.receiveMessage(
                                        (String msg) -> {
                                            probe.ref().tell("child-got-" + msg);
                                            return Behaviors.same();
                                        }),
                                "child");
                        return Behaviors.receiveMessage(
                                (String msg) -> {
                                    child.tell(msg);
                                    return Behaviors.same();
                                });
                    }),
                    "parent");

            parent.tell("ping");
            probe.expectMessage("child-got-ping");
        } finally {
            testKit.shutdown(testKit.system());
        }
    }

    @Test
    void actorTestKitNameSanity() {
        ActorTestKit testKit = ActorTestKit.create();
        try {
            assertThat(testKit.system().name()).isNotBlank();
        } finally {
            testKit.shutdown(testKit.system());
        }
    }
}

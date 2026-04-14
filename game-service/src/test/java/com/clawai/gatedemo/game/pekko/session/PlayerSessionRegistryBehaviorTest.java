package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerSessionRegistryBehaviorTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    private static void awaitDispatchCount(AtomicInteger c, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (c.get() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        assertThat(c.get()).isEqualTo(expected);
    }

    @Test
    void streamCloseStopsSessionWhenLastStreamForPlayer() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        CountDownLatch firstTwo = new CountDownLatch(2);
        CountDownLatch third = new CountDownLatch(1);
        GameMessageDispatcher dispatcher =
                new GameMessageDispatcher(new MessageHandlerRegistry(), null) {
                    @Override
                    public void dispatch(long playerId, int messageId, int seq, byte[] body) {
                        int n = dispatchCount.incrementAndGet();
                        if (n <= 2) {
                            firstTwo.countDown();
                        } else {
                            third.countDown();
                        }
                    }
                };

        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher), "reg");

        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(10L, 99L, 1, 1, new byte[0]));
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(10L, 99L, 1, 2, new byte[0]));
        assertThat(firstTwo.await(5, TimeUnit.SECONDS)).isTrue();

        registry.tell(new PlayerSessionRegistryBehavior.StreamClosed(10L));

        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(11L, 99L, 1, 3, new byte[0]));
        assertThat(third.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(dispatchCount.get()).isEqualTo(3);
    }

    @Test
    void twoStreamsSamePlayerHoldSessionUntilBothClose() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        GameMessageDispatcher dispatcher =
                new GameMessageDispatcher(new MessageHandlerRegistry(), null) {
                    @Override
                    public void dispatch(long playerId, int messageId, int seq, byte[] body) {
                        dispatchCount.incrementAndGet();
                    }
                };

        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher), "reg2");

        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(1L, 5L, 1, 1, new byte[0]));
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(2L, 5L, 1, 1, new byte[0]));
        awaitDispatchCount(dispatchCount, 2);

        registry.tell(new PlayerSessionRegistryBehavior.StreamClosed(1L));
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(2L, 5L, 1, 2, new byte[0]));
        awaitDispatchCount(dispatchCount, 3);

        registry.tell(new PlayerSessionRegistryBehavior.StreamClosed(2L));
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(3L, 5L, 1, 3, new byte[0]));
        awaitDispatchCount(dispatchCount, 4);
    }
}

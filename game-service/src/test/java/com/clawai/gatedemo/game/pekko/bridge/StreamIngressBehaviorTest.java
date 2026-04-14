package com.clawai.gatedemo.game.pekko.bridge;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StreamIngressBehaviorTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void dispatchRunsOnActorThread_notOnTellCallerThread() throws Exception {
        Thread teller = Thread.currentThread();
        AtomicReference<Thread> dispatchThread = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        GameMessageDispatcher recording = new GameMessageDispatcher(new MessageHandlerRegistry(), null) {
            @Override
            public void dispatch(long playerId, int messageId, int seq, byte[] body) {
                dispatchThread.set(Thread.currentThread());
                done.countDown();
            }
        };

        // ActorTestKit uses default test config; production uses bounded mailbox from application.conf.
        var ref = testKit.spawn(StreamIngressBehavior.create(recording), "ingress");

        ref.tell(new StreamIngressBehavior.InboundStreamFrame(1L, 2, 3, new byte[] {1}));

        done.await();
        assertThat(dispatchThread.get()).isNotNull().isNotSameAs(teller);
    }
}

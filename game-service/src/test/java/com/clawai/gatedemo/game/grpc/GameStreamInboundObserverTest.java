package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.pekko.bridge.StreamIngressBehavior;
import com.clawai.gatedemo.grpc.GameMessage;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures the gRPC stream callback path only enqueues to the ingress actor (spec:
 * {@code game-grpc-stream-actor-bridge}).
 */
class GameStreamInboundObserverTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    /** Dispatcher that must never receive {@code dispatch} from the inbound observer path. */
    private static final class FailOnDispatchDispatcher extends GameMessageDispatcher {
        FailOnDispatchDispatcher() {
            super(new MessageHandlerRegistry(), null);
        }

        @Override
        public void dispatch(long playerId, int messageId, int seq, byte[] body) {
            throw new AssertionError("dispatch must not run on gRPC callback thread for stream onNext");
        }
    }

    @Test
    void onNext_tellsIngress_andDoesNotInvokeDispatcherOnCallingThread() {
        TestProbe<StreamIngressBehavior.Command> probe = testKit.createTestProbe();
        FailOnDispatchDispatcher dispatcher = new FailOnDispatchDispatcher();
        StreamObserver<GameMessage> downstream = new StreamObserver<>() {
            @Override
            public void onNext(GameMessage value) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
        GameStreamInboundObserver obs = new GameStreamInboundObserver(
                probe.ref(), downstream, dispatcher, LoggerFactory.getLogger("test"));

        GameMessage msg = GameMessage.newBuilder()
                .setGateId("gate-1")
                .setPlayerId(42L)
                .setMsgId(7)
                .setSeq(1)
                .setBody(ByteString.copyFromUtf8("x"))
                .build();

        obs.onNext(msg);

        StreamIngressBehavior.InboundStreamFrame got =
                probe.expectMessageClass(StreamIngressBehavior.InboundStreamFrame.class);
        assertThat(got.playerId()).isEqualTo(42L);
        assertThat(got.messageId()).isEqualTo(7);
        assertThat(got.seq()).isEqualTo(1);
        assertThat(got.body()).isEqualTo(msg.getBody().toByteArray());
    }

    @Test
    void onCompleted_sendsShutdownToIngress() {
        TestProbe<StreamIngressBehavior.Command> probe = testKit.createTestProbe();
        GameMessageDispatcher dispatcher = new GameMessageDispatcher(new MessageHandlerRegistry(), null) {
            @Override
            public void dispatch(long playerId, int messageId, int seq, byte[] body) {
                // not used in this test
            }
        };
        StreamObserver<GameMessage> downstream = new StreamObserver<>() {
            @Override
            public void onNext(GameMessage value) {
            }

            @Override
            public void onError(Throwable t) {
            }

            @Override
            public void onCompleted() {
            }
        };
        GameStreamInboundObserver obs = new GameStreamInboundObserver(
                probe.ref(), downstream, dispatcher, LoggerFactory.getLogger("test"));

        obs.onCompleted();

        probe.expectMessageClass(StreamIngressBehavior.Shutdown.class);
    }
}

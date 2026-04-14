package com.clawai.gatedemo.game.pekko.bridge;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One stream ingress actor per Gate–Game {@code StreamCommunication} RPC: runs
 * {@link GameMessageDispatcher#dispatch} on the actor thread (not on gRPC/Netty callbacks).
 * <p>
 * See {@code openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/design.md} and
 * {@code docs/backpressure-design.md} (HTTP/2 flow control vs application mailbox).
 */
public final class StreamIngressBehavior {

    private StreamIngressBehavior() {}

    /** Protocol for the stream ingress actor. */
    public sealed interface Command permits InboundStreamFrame, Shutdown {}

    /** Inbound uplink frame mapped to {@link GameMessageDispatcher#dispatch} arguments. */
    public record InboundStreamFrame(long playerId, int messageId, int seq, byte[] body) implements Command {}

    /** Stop this actor when the gRPC stream ends. */
    public record Shutdown() implements Command {}

    public static Behavior<Command> create(GameMessageDispatcher dispatcher) {
        return Behaviors.setup(ctx -> Behaviors.receive(Command.class)
                .onMessage(InboundStreamFrame.class, frame -> {
                    try {
                        dispatcher.dispatch(frame.playerId(), frame.messageId(), frame.seq(), frame.body());
                    } catch (Exception e) {
                        ctx.getLog().error(
                                "Stream ingress dispatch failed: playerId={}, msgId={}",
                                frame.playerId(),
                                frame.messageId(),
                                e);
                    }
                    return Behaviors.same();
                })
                .onMessage(Shutdown.class, s -> Behaviors.stopped())
                .build());
    }
}

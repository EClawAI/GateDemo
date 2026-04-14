package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One Typed actor per online {@code playerId} (see {@link PlayerSessionRegistryBehavior}).
 * Invokes {@link GameMessageDispatcher#dispatch} on the actor thread.
 */
public final class PlayerSessionBehavior {

    private PlayerSessionBehavior() {}

    public sealed interface Command permits ProcessInbound {}

    public record ProcessInbound(int messageId, int seq, byte[] body) implements Command {}

    public static Behavior<Command> create(GameMessageDispatcher dispatcher, long playerId) {
        Behavior<Command> inner = Behaviors.setup(ctx -> Behaviors.receive(Command.class)
                .onMessage(ProcessInbound.class, m -> {
                    try {
                        dispatcher.dispatch(playerId, m.messageId(), m.seq(), m.body());
                    } catch (Exception e) {
                        ctx.getLog().error(
                                "PlayerSession dispatch failed: playerId={}, msgId={}",
                                playerId,
                                m.messageId(),
                                e);
                    }
                    return Behaviors.same();
                })
                .build());
        return Behaviors.supervise(inner).onFailure(SupervisorStrategy.stop());
    }
}

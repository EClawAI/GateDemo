package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One Typed actor per online {@code playerId} (see {@link PlayerSessionRegistryBehavior}).
 * Invokes {@link GameMessageDispatcher#dispatch} on the actor thread; wallet/plunder settlement is
 * in-memory for demo (see phase 6 for persistence boundaries).
 */
public final class PlayerSessionBehavior {

    private PlayerSessionBehavior() {}

    public sealed interface Command permits ProcessInbound, SettlePlunder {}

    public record ProcessInbound(int messageId, int seq, byte[] body) implements Command {}

    /**
     * Map/City asks victim player to commit plunder for a battle; idempotent by {@code battleId}
     * (see {@link PlunderDuplicate}).
     */
    public record SettlePlunder(long battleId, long requestedPlunder, ActorRef<PlunderSettleResponse> replyTo)
            implements Command {}

    private static final class WalletState {
        /** battleId -> committed actual plunder (idempotency). */
        final ConcurrentHashMap<Long, Long> battleToActual = new ConcurrentHashMap<>();
        /** Demo wallet; not yet wired to PlayerData Mongo. */
        long walletGold = 10_000L;
    }

    public static Behavior<Command> create(GameMessageDispatcher dispatcher, long playerId) {
        Behavior<Command> inner =
                Behaviors.setup(
                        ctx -> {
                            WalletState state = new WalletState();
                            return Behaviors.receive(Command.class)
                                    .onMessage(ProcessInbound.class, m -> onInbound(ctx, dispatcher, playerId, m))
                                    .onMessage(SettlePlunder.class, s -> onSettle(ctx, state, playerId, s))
                                    .build();
                        });
        return Behaviors.supervise(inner).onFailure(SupervisorStrategy.stop());
    }

    private static Behavior<Command> onInbound(
            ActorContext<Command> ctx, GameMessageDispatcher dispatcher, long playerId, ProcessInbound m) {
        try {
            dispatcher.dispatch(playerId, m.messageId(), m.seq(), m.body());
        } catch (Exception e) {
            ctx.getLog().error("PlayerSession dispatch failed: playerId={}, msgId={}", playerId, m.messageId(), e);
        }
        return Behaviors.same();
    }

    private static Behavior<Command> onSettle(
            ActorContext<Command> ctx, WalletState state, long playerId, SettlePlunder s) {
        Long prior = state.battleToActual.get(s.battleId());
        if (prior != null) {
            s.replyTo().tell(new PlunderDuplicate(s.battleId(), prior));
            return Behaviors.same();
        }
        if (s.requestedPlunder() <= 0) {
            s.replyTo().tell(new PlunderRejected(s.battleId(), "requestedPlunder must be positive"));
            return Behaviors.same();
        }
        long actual = Math.min(s.requestedPlunder(), state.walletGold);
        state.walletGold -= actual;
        state.battleToActual.put(s.battleId(), actual);
        s.replyTo().tell(new PlunderOk(s.battleId(), actual));
        return Behaviors.same();
    }
}

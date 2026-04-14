package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One Typed actor per online {@code playerId} (see {@link PlayerSessionRegistryBehavior}).
 * Invokes {@link GameMessageDispatcher#dispatch} on the actor thread; plunder settlement delegates to
 * {@link PlayerPlunderLedger}（生产为 Mongo，测试可用内存实现）。
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

    public static Behavior<Command> create(GameMessageDispatcher dispatcher, long playerId, PlayerPlunderLedger ledger) {
        Behavior<Command> inner =
                Behaviors.setup(
                        ctx ->
                                Behaviors.receive(Command.class)
                                        .onMessage(ProcessInbound.class, m -> onInbound(ctx, dispatcher, playerId, m))
                                        .onMessage(SettlePlunder.class, s -> onSettle(ctx, ledger, playerId, s))
                                        .build());
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
            ActorContext<Command> ctx, PlayerPlunderLedger ledger, long playerId, SettlePlunder s) {
        PlunderSettleResponse r = ledger.trySettle(playerId, s.battleId(), s.requestedPlunder());
        s.replyTo().tell(r);
        return Behaviors.same();
    }
}

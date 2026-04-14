package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * 每个在线 {@code playerId} 对应一个 Typed Actor（见 {@link PlayerSessionRegistryBehavior}）。
 * 在 Actor 线程上调用 {@link GameMessageDispatcher#dispatch}；掠夺结算委托给
 * {@link PlayerPlunderLedger}（生产环境为 Mongo，测试可用内存实现）。
 */
public final class PlayerSessionBehavior {

    private PlayerSessionBehavior() {}

    public sealed interface Command permits ProcessInbound, SettlePlunder {}

    public record ProcessInbound(int messageId, int seq, byte[] body) implements Command {}

    /**
     * 地图/City 请求受害玩家就某场战斗提交掠夺；按 {@code battleId} 幂等（见 {@link PlunderDuplicate}）。
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

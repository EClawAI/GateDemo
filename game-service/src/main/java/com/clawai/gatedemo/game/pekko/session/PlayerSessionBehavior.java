package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.SupervisorStrategy;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * 「每玩家一聚合」的在线会话载体：每个在线 {@code playerId} 对应一个 Typed Actor（见
 * {@link PlayerSessionRegistryBehavior}），在单邮箱内串行处理入站帧与个人权威侧效应；钱包与 {@code battleId}
 * 幂等语义以 {@link PlayerPlunderLedger} 为准（生产 Mongo / 测试内存）。地图侧由
 * {@link com.clawai.gatedemo.game.pekko.world.WorldMapSandboxBehavior} 通过 Ask 与本 Actor 协作。离线玩家是否常驻邮箱 / Sharding 另立 change，不在此强制。
 */
public final class PlayerSessionBehavior {

    private PlayerSessionBehavior() {}

    public sealed interface Command permits ProcessInbound, SettlePlunder {}

    public record ProcessInbound(int messageId, int seq, byte[] body) implements Command {}

    /**
     * 地图侧（沙盘或兼容期 City）请求受害玩家就某场战斗提交掠夺；按 {@code battleId} 幂等（见 {@link PlunderDuplicate}）。
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

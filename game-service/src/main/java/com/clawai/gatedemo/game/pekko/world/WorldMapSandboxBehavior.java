package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.persistence.CityWorldStatePersistence;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionBehavior;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import com.clawai.gatedemo.game.pekko.session.PlunderDuplicate;
import com.clawai.gatedemo.game.pekko.session.PlunderOk;
import com.clawai.gatedemo.game.pekko.session.PlunderRejected;
import com.clawai.gatedemo.game.pekko.session.PlunderSettleResponse;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatCalculator;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatInput;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatOutcome;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 每进程 × 每玩法模式一张大地图的 **唯一沙盘写入边界**（单 Typed Actor 邮箱）。
 *
 * <p>实例键：{@code game.id} + {@code gameplayId}（由 Spring 配置注入本 Actor 时绑定）；与 {@link PlayerSessionBehavior}
 * 通过 Ask 协作完成掠夺结算，写序与 {@code battleId} 幂等语义与 {@code game-plunder-settlement-ask-protocol} 一致。
 */
public final class WorldMapSandboxBehavior {

    private WorldMapSandboxBehavior() {}

    public sealed interface Command
            permits SandboxPing,
                    CityEnvelope,
                    CityPingSeq,
                    SettlePlunderVictim,
                    EvaluateFakeCombat,
                    FakeCombatDone,
                    GotSession,
                    GotPlunder {}

    /** 单测与健康检查：无 Mongo 依赖。 */
    public record SandboxPing(ActorRef<Integer> replyTo) implements Command {}

    public record CityEnvelope(long targetCityId, long attackerPlayerId, String payload) implements Command {}

    public record CityPingSeq(int seq, long targetCityId, ActorRef<Integer> replyTo) implements Command {}

    public record SettlePlunderVictim(
            long targetCityId,
            long battleId,
            long victimPlayerId,
            long requestedPlunder,
            ActorRef<PlunderSettlementResult> replyTo)
            implements Command {}

    /**
     * 示例：将纯函数战斗结算投递到 Worker 线程，结果经邮箱回投后再更新沙盘（见 {@link FakeCombatDone}）。
     */
    public record EvaluateFakeCombat(long battleId, long seed) implements Command {}

    record FakeCombatDone(FakeCombatOutcome outcome, Throwable err) implements Command {}

    record GotSession(SettlePlunderVictim original, Optional<ActorRef<PlayerSessionBehavior.Command>> session, Throwable err)
            implements Command {}

    record GotPlunder(SettlePlunderVictim original, PlunderSettleResponse response, Throwable err) implements Command {}

    public static Behavior<Command> create(
            String gameplayId,
            long persistenceRegionId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStateOrNull,
            FakeCombatCalculator combatCalculator,
            Executor workerExecutor) {
        return Behaviors.setup(
                ctx -> {
                    Map<Long, CityMapCacheState> cityCaches = new HashMap<>();
                    return Behaviors.receive(Command.class)
                            .onMessage(SandboxPing.class, p -> onPing(ctx, p))
                            .onMessage(
                                    CityEnvelope.class,
                                    e -> onEnvelope(ctx, persistenceRegionId, cityCaches, e, cityWorldStateOrNull))
                            .onMessage(CityPingSeq.class, p -> onCityPing(ctx, p))
                            .onMessage(
                                    SettlePlunderVictim.class,
                                    s -> startPlunder(ctx, playerSessionRegistry, s))
                            .onMessage(GotSession.class, g -> onGotSession(ctx, g))
                            .onMessage(
                                    GotPlunder.class,
                                    g -> onGotPlunder(ctx, persistenceRegionId, cityCaches, g, cityWorldStateOrNull))
                            .onMessage(
                                    EvaluateFakeCombat.class,
                                    e -> onEvaluateFakeCombat(ctx, e, combatCalculator, workerExecutor))
                            .onMessage(FakeCombatDone.class, d -> onFakeCombatDone(ctx, gameplayId, d))
                            .build();
                });
    }

    private static Behavior<Command> onPing(ActorContext<Command> ctx, SandboxPing p) {
        p.replyTo().tell(1);
        return Behaviors.same();
    }

    private static Behavior<Command> onEnvelope(
            ActorContext<Command> ctx,
            long persistenceRegionId,
            Map<Long, CityMapCacheState> cityCaches,
            CityEnvelope e,
            CityWorldStatePersistence cityWorldStateOrNull) {
        CityMapCacheState cache = cityCaches.computeIfAbsent(e.targetCityId(), id -> loadCache(persistenceRegionId, id, cityWorldStateOrNull));
        cache.putTile("lastAttacker", (int) (e.attackerPlayerId() % Integer.MAX_VALUE));
        persistIfNeeded(ctx, persistenceRegionId, e.targetCityId(), cache, cityWorldStateOrNull);
        return Behaviors.same();
    }

    private static CityMapCacheState loadCache(
            long persistenceRegionId, long cityId, CityWorldStatePersistence cityWorldStateOrNull) {
        CityMapCacheState cache = new CityMapCacheState();
        if (cityWorldStateOrNull != null) {
            Map<String, Integer> loaded = cityWorldStateOrNull.loadSnapshot(persistenceRegionId, cityId);
            for (var e : loaded.entrySet()) {
                cache.putTile(e.getKey(), e.getValue());
            }
        }
        return cache;
    }

    private static Behavior<Command> onCityPing(ActorContext<Command> ctx, CityPingSeq p) {
        p.replyTo().tell(p.seq());
        return Behaviors.same();
    }

    private static void persistIfNeeded(
            ActorContext<Command> ctx,
            long regionId,
            long cityId,
            CityMapCacheState cache,
            CityWorldStatePersistence persistence) {
        if (persistence == null) {
            return;
        }
        try {
            persistence.saveSnapshot(regionId, cityId, cache.snapshot());
        } catch (Exception e) {
            ctx.getLog().error("Sandbox world state save failed: regionId={} cityId={}", regionId, cityId, e);
        }
    }

    private static Behavior<Command> startPlunder(
            ActorContext<Command> ctx,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            SettlePlunderVictim s) {
        if (playerSessionRegistry == null) {
            s.replyTo().tell(new PlunderSettlementResult.Failed("playerSessionRegistry not configured"));
            return Behaviors.same();
        }
        Duration askTimeout = Duration.ofSeconds(5);
        CompletionStage<Optional<ActorRef<PlayerSessionBehavior.Command>>> sessionStage =
                AskPattern.ask(
                        playerSessionRegistry,
                        replyTo -> new PlayerSessionRegistryBehavior.GetPlayerSession(s.victimPlayerId(), replyTo),
                        askTimeout,
                        ctx.getSystem().scheduler());
        ctx.pipeToSelf(sessionStage, (opt, err) -> new GotSession(s, opt, err));
        return Behaviors.same();
    }

    private static Behavior<Command> onGotSession(ActorContext<Command> ctx, GotSession g) {
        SettlePlunderVictim s = g.original();
        if (g.err() != null) {
            ctx.getLog().warn("GetPlayerSession failed: battleId={}", s.battleId(), g.err());
            s.replyTo().tell(new PlunderSettlementResult.Failed("registry ask failed: " + g.err().getMessage()));
            return Behaviors.same();
        }
        if (g.session().isEmpty()) {
            s.replyTo().tell(new PlunderSettlementResult.Failed("victim offline"));
            return Behaviors.same();
        }
        ActorRef<PlayerSessionBehavior.Command> session = g.session().get();
        Duration askTimeout = Duration.ofSeconds(5);
        CompletionStage<PlunderSettleResponse> plunderStage =
                AskPattern.ask(
                        session,
                        (ActorRef<PlunderSettleResponse> replyTo) ->
                                new PlayerSessionBehavior.SettlePlunder(s.battleId(), s.requestedPlunder(), replyTo),
                        askTimeout,
                        ctx.getSystem().scheduler());
        ctx.pipeToSelf(plunderStage, (resp, err) -> new GotPlunder(s, resp, err));
        return Behaviors.same();
    }

    private static Behavior<Command> onGotPlunder(
            ActorContext<Command> ctx,
            long persistenceRegionId,
            Map<Long, CityMapCacheState> cityCaches,
            GotPlunder g,
            CityWorldStatePersistence cityWorldStateOrNull) {
        SettlePlunderVictim s = g.original();
        if (g.err() != null) {
            ctx.getLog().warn("SettlePlunder ask failed: battleId={}", s.battleId(), g.err());
            s.replyTo().tell(new PlunderSettlementResult.Failed("player ask failed: " + g.err().getMessage()));
            return Behaviors.same();
        }
        PlunderSettleResponse r = g.response();
        CityMapCacheState cache =
                cityCaches.computeIfAbsent(
                        s.targetCityId(), id -> loadCache(persistenceRegionId, id, cityWorldStateOrNull));
        switch (r) {
            case PlunderOk ok -> {
                cache.putTile("battle-" + ok.battleId(), (int) Math.min(ok.actualAmount(), Integer.MAX_VALUE));
                persistIfNeeded(ctx, persistenceRegionId, s.targetCityId(), cache, cityWorldStateOrNull);
                s.replyTo().tell(new PlunderSettlementResult.Ok(ok.battleId(), ok.actualAmount()));
            }
            case PlunderDuplicate dup -> {
                cache.putTile("battle-" + dup.battleId(), (int) Math.min(dup.actualAmount(), Integer.MAX_VALUE));
                persistIfNeeded(ctx, persistenceRegionId, s.targetCityId(), cache, cityWorldStateOrNull);
                s.replyTo().tell(new PlunderSettlementResult.Ok(dup.battleId(), dup.actualAmount()));
            }
            case PlunderRejected rej -> s.replyTo().tell(new PlunderSettlementResult.Failed(rej.reason()));
        }
        return Behaviors.same();
    }

    private static Behavior<Command> onEvaluateFakeCombat(
            ActorContext<Command> ctx,
            EvaluateFakeCombat e,
            FakeCombatCalculator combatCalculator,
            Executor workerExecutor) {
        CompletionStage<FakeCombatOutcome> stage =
                CompletableFuture.supplyAsync(
                        () -> combatCalculator.compute(new FakeCombatInput(e.battleId(), e.seed())), workerExecutor);
        ctx.pipeToSelf(stage, FakeCombatDone::new);
        return Behaviors.same();
    }

    private static Behavior<Command> onFakeCombatDone(ActorContext<Command> ctx, String gameplayId, FakeCombatDone d) {
        if (d.err() != null) {
            ctx.getLog().warn("Fake combat worker failed: gameplayId={}", gameplayId, d.err());
            return Behaviors.same();
        }
        if (d.outcome() == null) {
            ctx.getLog().warn("Fake combat outcome missing: gameplayId={}", gameplayId);
            return Behaviors.same();
        }
        FakeCombatOutcome o = d.outcome();
        ctx.getLog().debug(
                "Sandbox applied fake combat outcome: gameplayId={} battleId={} win={}",
                gameplayId,
                o.battleId(),
                o.attackerWins());
        return Behaviors.same();
    }
}


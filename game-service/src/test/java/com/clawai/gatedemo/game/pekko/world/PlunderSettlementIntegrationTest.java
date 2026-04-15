package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.pekko.session.InMemoryPlayerPlunderLedger;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import com.clawai.gatedemo.game.pekko.worker.DefaultFakeCombatCalculator;
import com.clawai.gatedemo.game.pekko.worker.FakeCombatCalculator;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 沙盘 → Player Ask with {@code battleId} idempotency（与 City 路径语义一致）。
 */
class PlunderSettlementIntegrationTest {

    private final ActorTestKit testKit = ActorTestKit.create();
    private final Executor workerExecutor = ForkJoinPool.commonPool();
    private final FakeCombatCalculator combatCalculator = new DefaultFakeCombatCalculator();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void settlePlunder_thenDuplicateBattleId_returnsSameActual() {
        GameMessageDispatcher dispatcher = new GameMessageDispatcher(new MessageHandlerRegistry(), null);
        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher, new InMemoryPlayerPlunderLedger()), "reg-plunder");
        ActorRef<WorldMapSandboxBehavior.Command> sandbox =
                testKit.spawn(
                        WorldMapSandboxBehavior.create(
                                "test", 0L, registry, null, combatCalculator, workerExecutor),
                        "sandbox-plunder");

        long victim = 99L;
        long streamId = 1L;
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(streamId, victim, 0, 0, new byte[0]));

        long cityId = 8L;
        long battleId = 9001L;

        TestProbe<PlunderSettlementResult> result = testKit.createTestProbe();
        sandbox.tell(
                new WorldMapSandboxBehavior.SettlePlunderVictim(cityId, battleId, victim, 500L, result.ref()));

        PlunderSettlementResult first = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(first).isInstanceOf(PlunderSettlementResult.Ok.class);
        assertThat(((PlunderSettlementResult.Ok) first).actualPlunder()).isEqualTo(500L);

        sandbox.tell(
                new WorldMapSandboxBehavior.SettlePlunderVictim(cityId, battleId, victim, 500L, result.ref()));
        PlunderSettlementResult second = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(second).isInstanceOf(PlunderSettlementResult.Ok.class);
        assertThat(((PlunderSettlementResult.Ok) second).actualPlunder()).isEqualTo(500L);
    }

    @Test
    void settlePlunder_victimOffline_fails() {
        GameMessageDispatcher dispatcher = new GameMessageDispatcher(new MessageHandlerRegistry(), null);
        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher, new InMemoryPlayerPlunderLedger()), "reg-offline");
        ActorRef<WorldMapSandboxBehavior.Command> sandbox =
                testKit.spawn(
                        WorldMapSandboxBehavior.create(
                                "test", 0L, registry, null, combatCalculator, workerExecutor),
                        "sandbox-offline");

        TestProbe<PlunderSettlementResult> result = testKit.createTestProbe();
        sandbox.tell(
                new WorldMapSandboxBehavior.SettlePlunderVictim(2L, 42L, 777L, 100L, result.ref()));

        PlunderSettlementResult r = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(r).isInstanceOf(PlunderSettlementResult.Failed.class);
        assertThat(((PlunderSettlementResult.Failed) r).reason()).contains("offline");
    }
}

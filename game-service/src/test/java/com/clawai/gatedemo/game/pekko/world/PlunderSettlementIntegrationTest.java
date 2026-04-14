package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.pekko.session.InMemoryPlayerPlunderLedger;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Map → Player Ask with {@code battleId} idempotency (phase 5).
 */
class PlunderSettlementIntegrationTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void settlePlunder_thenDuplicateBattleId_returnsSameActual() {
        GameMessageDispatcher dispatcher = new GameMessageDispatcher(new MessageHandlerRegistry(), null);
        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher, new InMemoryPlayerPlunderLedger()), "reg-plunder");
        ActorRef<WorldRegistryBehavior.Command> world =
                testKit.spawn(WorldRegistryBehavior.create(registry, null), "world-plunder");

        long victim = 99L;
        long streamId = 1L;
        registry.tell(new PlayerSessionRegistryBehavior.RouteInbound(streamId, victim, 0, 0, new byte[0]));

        long regionId = 3L;
        long cityId = 8L;
        long battleId = 9001L;

        TestProbe<PlunderSettlementResult> result = testKit.createTestProbe();
        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        regionId,
                        cityId,
                        new CityBehavior.SettlePlunderVictim(cityId, battleId, victim, 500L, result.ref())));

        PlunderSettlementResult first = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(first).isInstanceOf(PlunderSettlementResult.Ok.class);
        assertThat(((PlunderSettlementResult.Ok) first).actualPlunder()).isEqualTo(500L);

        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        regionId,
                        cityId,
                        new CityBehavior.SettlePlunderVictim(cityId, battleId, victim, 500L, result.ref())));
        PlunderSettlementResult second = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(second).isInstanceOf(PlunderSettlementResult.Ok.class);
        assertThat(((PlunderSettlementResult.Ok) second).actualPlunder()).isEqualTo(500L);
    }

    @Test
    void settlePlunder_victimOffline_fails() {
        GameMessageDispatcher dispatcher = new GameMessageDispatcher(new MessageHandlerRegistry(), null);
        ActorRef<PlayerSessionRegistryBehavior.Command> registry =
                testKit.spawn(PlayerSessionRegistryBehavior.create(dispatcher, new InMemoryPlayerPlunderLedger()), "reg-offline");
        ActorRef<WorldRegistryBehavior.Command> world =
                testKit.spawn(WorldRegistryBehavior.create(registry, null), "world-offline");

        TestProbe<PlunderSettlementResult> result = testKit.createTestProbe();
        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        1L,
                        2L,
                        new CityBehavior.SettlePlunderVictim(2L, 42L, 777L, 100L, result.ref())));

        PlunderSettlementResult r = result.expectMessageClass(PlunderSettlementResult.class);
        assertThat(r).isInstanceOf(PlunderSettlementResult.Failed.class);
        assertThat(((PlunderSettlementResult.Failed) r).reason()).contains("offline");
    }
}

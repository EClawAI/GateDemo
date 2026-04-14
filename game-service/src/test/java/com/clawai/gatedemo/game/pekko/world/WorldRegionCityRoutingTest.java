package com.clawai.gatedemo.game.pekko.world;

import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit;
import org.apache.pekko.actor.testkit.typed.javadsl.TestProbe;
import org.apache.pekko.actor.typed.ActorRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class WorldRegionCityRoutingTest {

    private final ActorTestKit testKit = ActorTestKit.create();

    @AfterEach
    void shutdown() {
        testKit.shutdownTestKit();
    }

    @Test
    void worldRegistry_routesSameCity_serialOrder() {
        TestProbe<Integer> probe = testKit.createTestProbe();
        ActorRef<WorldRegistryBehavior.Command> world =
                testKit.spawn(WorldRegistryBehavior.create(), "world");

        long region = 7L;
        long cityId = 42L;
        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        region, cityId, new CityBehavior.CityPingSeq(1, cityId, probe.ref())));
        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        region, cityId, new CityBehavior.CityPingSeq(2, cityId, probe.ref())));
        world.tell(
                new WorldRegistryBehavior.RouteToCity(
                        region, cityId, new CityBehavior.CityPingSeq(3, cityId, probe.ref())));

        probe.expectMessage(1);
        probe.expectMessage(2);
        probe.expectMessage(3);
    }
}

package com.clawai.gatedemo.game.pekko.world;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;

/** Top-level registry: routes to Region → City. */
public final class WorldRegistryBehavior {

    private WorldRegistryBehavior() {}

    public sealed interface Command permits RouteToCity {}

    public record RouteToCity(long regionId, long cityId, CityBehavior.CityCommand cityCommand)
            implements Command {}

    public static Behavior<Command> create() {
        return Behaviors.setup(ctx -> {
            Map<Long, ActorRef<RegionBehavior.Command>> regions = new HashMap<>();
            return Behaviors.receive(Command.class)
                    .onMessage(RouteToCity.class, r -> onRoute(ctx, regions, r))
                    .build();
        });
    }

    private static Behavior<Command> onRoute(
            ActorContext<Command> ctx, Map<Long, ActorRef<RegionBehavior.Command>> regions, RouteToCity r) {
        ActorRef<RegionBehavior.Command> region =
                regions.computeIfAbsent(
                        r.regionId(), rid -> ctx.spawnAnonymous(RegionBehavior.create(rid)));
        region.tell(new RegionBehavior.ForwardToCity(r.cityId(), r.cityCommand()));
        return Behaviors.same();
    }
}

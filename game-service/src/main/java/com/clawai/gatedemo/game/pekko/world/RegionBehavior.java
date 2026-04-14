package com.clawai.gatedemo.game.pekko.world;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;

/** One Typed actor per regionId; lazily spawns anonymous {@link CityBehavior} children. */
public final class RegionBehavior {

    private RegionBehavior() {}

    public sealed interface Command permits ForwardToCity {}

    public record ForwardToCity(long cityId, CityBehavior.CityCommand cityCommand) implements Command {}

    public static Behavior<Command> create(long regionId) {
        return Behaviors.setup(ctx -> {
            Map<Long, ActorRef<CityBehavior.CityCommand>> cities = new HashMap<>();
            return Behaviors.receive(Command.class)
                    .onMessage(ForwardToCity.class, f -> onForward(ctx, regionId, cities, f))
                    .build();
        });
    }

    private static Behavior<Command> onForward(
            ActorContext<Command> ctx,
            long regionId,
            Map<Long, ActorRef<CityBehavior.CityCommand>> cities,
            ForwardToCity f) {
        ActorRef<CityBehavior.CityCommand> city =
                cities.computeIfAbsent(
                        f.cityId(),
                        id -> ctx.spawnAnonymous(CityBehavior.create(regionId, id)));
        city.tell(f.cityCommand());
        return Behaviors.same();
    }
}

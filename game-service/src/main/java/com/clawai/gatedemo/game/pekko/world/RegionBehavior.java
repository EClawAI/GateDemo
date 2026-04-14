package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
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

    public record ForwardToCity(long cityId, CityBehavior.CityMessage cityMessage) implements Command {}

    public static Behavior<Command> create(
            long regionId, ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry) {
        return Behaviors.setup(ctx -> {
            Map<Long, ActorRef<CityBehavior.CityMessage>> cities = new HashMap<>();
            return Behaviors.receive(Command.class)
                    .onMessage(ForwardToCity.class, f -> onForward(ctx, regionId, cities, playerSessionRegistry, f))
                    .build();
        });
    }

    private static Behavior<Command> onForward(
            ActorContext<Command> ctx,
            long regionId,
            Map<Long, ActorRef<CityBehavior.CityMessage>> cities,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            ForwardToCity f) {
        ActorRef<CityBehavior.CityMessage> city =
                cities.computeIfAbsent(
                        f.cityId(),
                        id -> ctx.spawnAnonymous(CityBehavior.create(regionId, id, playerSessionRegistry, null)));
        city.tell(f.cityMessage());
        return Behaviors.same();
    }
}

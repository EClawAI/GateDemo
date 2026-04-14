package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.persistence.CityWorldStatePersistence;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;

/** 每个 regionId 一个 Typed Actor；按需匿名 spawn {@link CityBehavior} 子 Actor。 */
public final class RegionBehavior {

    private RegionBehavior() {}

    public sealed interface Command permits ForwardToCity {}

    public record ForwardToCity(long cityId, CityBehavior.CityMessage cityMessage) implements Command {}

    public static Behavior<Command> create(
            long regionId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStateOrNull) {
        return Behaviors.setup(ctx -> {
            Map<Long, ActorRef<CityBehavior.CityMessage>> cities = new HashMap<>();
            return Behaviors.receive(Command.class)
                    .onMessage(
                            ForwardToCity.class,
                            f -> onForward(ctx, regionId, cities, playerSessionRegistry, cityWorldStateOrNull, f))
                    .build();
        });
    }

    private static Behavior<Command> onForward(
            ActorContext<Command> ctx,
            long regionId,
            Map<Long, ActorRef<CityBehavior.CityMessage>> cities,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStateOrNull,
            ForwardToCity f) {
        ActorRef<CityBehavior.CityMessage> city =
                cities.computeIfAbsent(
                        f.cityId(),
                        id ->
                                ctx.spawnAnonymous(
                                        CityBehavior.create(regionId, id, playerSessionRegistry, null, cityWorldStateOrNull)));
        city.tell(f.cityMessage());
        return Behaviors.same();
    }
}

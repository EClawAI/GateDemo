package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.persistence.CityWorldStatePersistence;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.Map;

/** 顶层注册表：路由到 Region → City。 */
public final class WorldRegistryBehavior {

    public sealed interface Command permits RouteToCity {}

    public record RouteToCity(long regionId, long cityId, CityBehavior.CityMessage cityMessage) implements Command {}

    public static Behavior<Command> create(
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStateOrNull) {
        return Behaviors.setup(ctx -> new WorldRegistryBehavior(ctx, playerSessionRegistry, cityWorldStateOrNull).running());
    }

    private final ActorContext<Command> ctx;
    private final ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry;
    private final CityWorldStatePersistence cityWorldStateOrNull;
    private final Map<Long, ActorRef<RegionBehavior.Command>> regions = new HashMap<>();

    private WorldRegistryBehavior(
            ActorContext<Command> ctx,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            CityWorldStatePersistence cityWorldStateOrNull) {
        this.ctx = ctx;
        this.playerSessionRegistry = playerSessionRegistry;
        this.cityWorldStateOrNull = cityWorldStateOrNull;
    }

    private Behavior<Command> running() {
        return Behaviors.receive(Command.class).onMessage(RouteToCity.class, this::onRoute).build();
    }

    private Behavior<Command> onRoute(RouteToCity r) {
        ActorRef<RegionBehavior.Command> region =
                regions.computeIfAbsent(
                        r.regionId(),
                        rid -> ctx.spawnAnonymous(RegionBehavior.create(rid, playerSessionRegistry, cityWorldStateOrNull)));
        region.tell(new RegionBehavior.ForwardToCity(r.cityId(), r.cityMessage()));
        return Behaviors.same();
    }
}

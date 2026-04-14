package com.clawai.gatedemo.game.pekko.world;

import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * One Typed actor per (regionId, cityId). Single-writer for {@link CityMapCacheState}.
 */
public final class CityBehavior {

    private CityBehavior() {}

    public sealed interface CityCommand permits CityEnvelope, CityPingSeq {}

    /** Declares which city the command applies to; must match this actor's cityId. */
    public record CityEnvelope(long targetCityId, long attackerPlayerId, String payload)
            implements CityCommand {}

    /** Test/probe: reply with seq after processing if target matches. */
    public record CityPingSeq(int seq, long targetCityId, ActorRef<Integer> replyTo) implements CityCommand {}

    public static Behavior<CityCommand> create(long regionId, long cityId) {
        return create(regionId, cityId, null);
    }

    /** @param onAcceptedOrNull optional hook for tests (runs when a command is accepted). */
    public static Behavior<CityCommand> create(long regionId, long cityId, Runnable onAcceptedOrNull) {
        return Behaviors.setup(ctx -> {
            CityMapCacheState cache = new CityMapCacheState();
            return Behaviors.receive(CityCommand.class)
                    .onMessage(CityEnvelope.class, e -> handleEnvelope(ctx, regionId, cityId, cache, e, onAcceptedOrNull))
                    .onMessage(CityPingSeq.class, p -> handlePing(ctx, cityId, p, onAcceptedOrNull))
                    .build();
        });
    }

    private static Behavior<CityCommand> handleEnvelope(
            org.apache.pekko.actor.typed.javadsl.ActorContext<CityCommand> ctx,
            long regionId,
            long cityId,
            CityMapCacheState cache,
            CityEnvelope e,
            Runnable onAcceptedOrNull) {
        if (e.targetCityId() != cityId) {
            ctx.getLog()
                    .warn(
                            "Reject CityEnvelope: actor cityId={} regionId={} but targetCityId={}",
                            cityId,
                            regionId,
                            e.targetCityId());
            return Behaviors.same();
        }
        if (onAcceptedOrNull != null) {
            onAcceptedOrNull.run();
        }
        cache.putTile("lastAttacker", (int) (e.attackerPlayerId() % Integer.MAX_VALUE));
        return Behaviors.same();
    }

    private static Behavior<CityCommand> handlePing(
            org.apache.pekko.actor.typed.javadsl.ActorContext<CityCommand> ctx,
            long cityId,
            CityPingSeq p,
            Runnable onAcceptedOrNull) {
        if (p.targetCityId() != cityId) {
            ctx.getLog().warn("Reject CityPingSeq: cityId={} targetCityId={}", cityId, p.targetCityId());
            return Behaviors.same();
        }
        if (onAcceptedOrNull != null) {
            onAcceptedOrNull.run();
        }
        p.replyTo().tell(p.seq());
        return Behaviors.same();
    }
}

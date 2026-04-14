package com.clawai.gatedemo.game.pekko.world;

import com.clawai.gatedemo.game.persistence.CityWorldStatePersistence;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionBehavior;
import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import com.clawai.gatedemo.game.pekko.session.PlunderDuplicate;
import com.clawai.gatedemo.game.pekko.session.PlunderOk;
import com.clawai.gatedemo.game.pekko.session.PlunderRejected;
import com.clawai.gatedemo.game.pekko.session.PlunderSettleResponse;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.AskPattern;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * One Typed actor per (regionId, cityId). Single-writer for {@link CityMapCacheState}; Map→Player plunder
 * uses Ask + {@code battleId} idempotency on {@link PlayerSessionBehavior}.
 */
public final class CityBehavior {

    private CityBehavior() {}

    public sealed interface CityMessage
            permits CityEnvelope, CityPingSeq, SettlePlunderVictim, GotSession, GotPlunder {}

    public record CityEnvelope(long targetCityId, long attackerPlayerId, String payload) implements CityMessage {}

    public record CityPingSeq(int seq, long targetCityId, ActorRef<Integer> replyTo) implements CityMessage {}

    /**
     * City (map authority) asks victim's {@link PlayerSessionBehavior} to settle plunder; async pipeline
     * completes with {@link PlunderSettlementResult} on {@code replyTo}.
     */
    public record SettlePlunderVictim(
            long targetCityId,
            long battleId,
            long victimPlayerId,
            long requestedPlunder,
            ActorRef<PlunderSettlementResult> replyTo)
            implements CityMessage {}

    record GotSession(SettlePlunderVictim original, Optional<ActorRef<PlayerSessionBehavior.Command>> session, Throwable err)
            implements CityMessage {}

    record GotPlunder(SettlePlunderVictim original, PlunderSettleResponse response, Throwable err) implements CityMessage {}

    public static Behavior<CityMessage> create(long regionId, long cityId) {
        return create(regionId, cityId, null, null, null);
    }

    public static Behavior<CityMessage> create(
            long regionId,
            long cityId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            Runnable onAcceptedOrNull) {
        return create(regionId, cityId, playerSessionRegistry, onAcceptedOrNull, null);
    }

    public static Behavior<CityMessage> create(
            long regionId,
            long cityId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            Runnable onAcceptedOrNull,
            CityWorldStatePersistence cityWorldStateOrNull) {
        return Behaviors.setup(
                ctx -> {
                    CityMapCacheState cache = new CityMapCacheState();
                    if (cityWorldStateOrNull != null) {
                        Map<String, Integer> loaded = cityWorldStateOrNull.loadSnapshot(regionId, cityId);
                        for (var e : loaded.entrySet()) {
                            cache.putTile(e.getKey(), e.getValue());
                        }
                    }
                    return Behaviors.receive(CityMessage.class)
                            .onMessage(
                                    CityEnvelope.class,
                                    e ->
                                            handleEnvelope(
                                                    ctx,
                                                    regionId,
                                                    cityId,
                                                    cache,
                                                    e,
                                                    onAcceptedOrNull,
                                                    cityWorldStateOrNull))
                            .onMessage(CityPingSeq.class, p -> handlePing(ctx, cityId, p, onAcceptedOrNull))
                            .onMessage(SettlePlunderVictim.class, s -> startPlunder(ctx, regionId, cityId, playerSessionRegistry, s))
                            .onMessage(GotSession.class, g -> onGotSession(ctx, cityId, playerSessionRegistry, g))
                            .onMessage(
                                    GotPlunder.class,
                                    g -> onGotPlunder(ctx, regionId, cityId, cache, g, cityWorldStateOrNull))
                            .build();
                });
    }

    private static Behavior<CityMessage> handleEnvelope(
            ActorContext<CityMessage> ctx,
            long regionId,
            long cityId,
            CityMapCacheState cache,
            CityEnvelope e,
            Runnable onAcceptedOrNull,
            CityWorldStatePersistence cityWorldStateOrNull) {
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
        persistIfNeeded(ctx, regionId, cityId, cache, cityWorldStateOrNull);
        return Behaviors.same();
    }

    private static void persistIfNeeded(
            ActorContext<CityMessage> ctx,
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
            ctx.getLog().error("City world state save failed: regionId={} cityId={}", regionId, cityId, e);
        }
    }

    private static Behavior<CityMessage> handlePing(
            ActorContext<CityMessage> ctx,
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

    private static Behavior<CityMessage> startPlunder(
            ActorContext<CityMessage> ctx,
            long regionId,
            long cityId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            SettlePlunderVictim s) {
        if (playerSessionRegistry == null) {
            s.replyTo().tell(new PlunderSettlementResult.Failed("playerSessionRegistry not configured"));
            return Behaviors.same();
        }
        if (s.targetCityId() != cityId) {
            ctx.getLog()
                    .warn(
                            "Reject SettlePlunderVictim: cityId={} regionId={} targetCityId={}",
                            cityId,
                            regionId,
                            s.targetCityId());
            s.replyTo().tell(new PlunderSettlementResult.Failed("targetCityId mismatch"));
            return Behaviors.same();
        }
        Duration askTimeout = Duration.ofSeconds(5);
        CompletionStage<Optional<ActorRef<PlayerSessionBehavior.Command>>> sessionStage =
                AskPattern.ask(
                        playerSessionRegistry,
                        replyTo -> new PlayerSessionRegistryBehavior.GetPlayerSession(s.victimPlayerId(), replyTo),
                        askTimeout,
                        ctx.getSystem().scheduler());
        ctx.pipeToSelf(
                sessionStage,
                (opt, err) -> new GotSession(s, opt, err));
        return Behaviors.same();
    }

    private static Behavior<CityMessage> onGotSession(
            ActorContext<CityMessage> ctx,
            long cityId,
            ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry,
            GotSession g) {
        SettlePlunderVictim s = g.original();
        if (g.err() != null) {
            ctx.getLog().warn("GetPlayerSession failed: cityId={} battleId={}", cityId, s.battleId(), g.err());
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

    private static Behavior<CityMessage> onGotPlunder(
            ActorContext<CityMessage> ctx,
            long regionId,
            long cityId,
            CityMapCacheState cache,
            GotPlunder g,
            CityWorldStatePersistence cityWorldStateOrNull) {
        SettlePlunderVictim s = g.original();
        if (g.err() != null) {
            ctx.getLog().warn("SettlePlunder ask failed: cityId={} battleId={}", cityId, s.battleId(), g.err());
            s.replyTo().tell(new PlunderSettlementResult.Failed("player ask failed: " + g.err().getMessage()));
            return Behaviors.same();
        }
        PlunderSettleResponse r = g.response();
        switch (r) {
            case PlunderOk ok -> {
                cache.putTile("battle-" + ok.battleId(), (int) Math.min(ok.actualAmount(), Integer.MAX_VALUE));
                persistIfNeeded(ctx, regionId, cityId, cache, cityWorldStateOrNull);
                s.replyTo().tell(new PlunderSettlementResult.Ok(ok.battleId(), ok.actualAmount()));
            }
            case PlunderDuplicate dup -> {
                cache.putTile("battle-" + dup.battleId(), (int) Math.min(dup.actualAmount(), Integer.MAX_VALUE));
                persistIfNeeded(ctx, regionId, cityId, cache, cityWorldStateOrNull);
                s.replyTo().tell(new PlunderSettlementResult.Ok(dup.battleId(), dup.actualAmount()));
            }
            case PlunderRejected rej -> s.replyTo().tell(new PlunderSettlementResult.Failed(rej.reason()));
        }
        return Behaviors.same();
    }
}

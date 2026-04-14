package com.clawai.gatedemo.game.pekko.session;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.ActorContext;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Routes inbound stream frames to {@link PlayerSessionBehavior} by {@code playerId}, with
 * per-stream refcount so sessions stop when no active stream references a player.
 */
public final class PlayerSessionRegistryBehavior {

    public sealed interface Command permits RouteInbound, StreamClosed, PlayerSessionTerminated, GetPlayerSession {}

    public record RouteInbound(long streamId, long playerId, int messageId, int seq, byte[] body)
            implements Command {}

    public record StreamClosed(long streamId) implements Command {}

    /** Fired when a watched {@link PlayerSessionBehavior} stops; {@code ref} avoids clearing a replacement session. */
    public record PlayerSessionTerminated(long playerId, ActorRef<PlayerSessionBehavior.Command> ref)
            implements Command {}

    /** Resolve current session actor for cross-Actor Ask (e.g. City → Player plunder settlement). */
    public record GetPlayerSession(long playerId, ActorRef<Optional<ActorRef<PlayerSessionBehavior.Command>>> replyTo)
            implements Command {}

    public static Behavior<Command> create(GameMessageDispatcher dispatcher, PlayerPlunderLedger plunderLedger) {
        return Behaviors.setup(ctx -> new PlayerSessionRegistryBehavior(ctx, dispatcher, plunderLedger).running());
    }

    private final ActorContext<Command> context;
    private final GameMessageDispatcher dispatcher;
    private final PlayerPlunderLedger plunderLedger;
    private final Map<Long, ActorRef<PlayerSessionBehavior.Command>> sessions = new HashMap<>();
    /** streamId -> playerIds that have received at least one frame on this stream */
    private final Map<Long, Set<Long>> streamToPlayers = new HashMap<>();
    /** playerId -> number of distinct streams currently referencing this player */
    private final Map<Long, Integer> playerRefCount = new HashMap<>();

    private PlayerSessionRegistryBehavior(
            ActorContext<Command> context, GameMessageDispatcher dispatcher, PlayerPlunderLedger plunderLedger) {
        this.context = context;
        this.dispatcher = dispatcher;
        this.plunderLedger = plunderLedger;
    }

    private Behavior<Command> running() {
        return Behaviors.receive(Command.class)
                .onMessage(RouteInbound.class, this::onRouteInbound)
                .onMessage(StreamClosed.class, this::onStreamClosed)
                .onMessage(PlayerSessionTerminated.class, this::onPlayerSessionTerminated)
                .onMessage(GetPlayerSession.class, this::onGetPlayerSession)
                .build();
    }

    private Behavior<Command> onRouteInbound(RouteInbound r) {
        Set<Long> players = streamToPlayers.computeIfAbsent(r.streamId(), k -> new HashSet<>());
        if (players.add(r.playerId())) {
            int prev = playerRefCount.getOrDefault(r.playerId(), 0);
            playerRefCount.put(r.playerId(), prev + 1);
            if (prev == 0) {
                spawnSession(r.playerId());
            }
        }
        ActorRef<PlayerSessionBehavior.Command> session = sessions.get(r.playerId());
        if (session != null) {
            session.tell(new PlayerSessionBehavior.ProcessInbound(r.messageId(), r.seq(), r.body()));
        }
        return Behaviors.same();
    }

    private void spawnSession(long playerId) {
        Behavior<PlayerSessionBehavior.Command> b = PlayerSessionBehavior.create(dispatcher, playerId, plunderLedger);
        // Anonymous avoids InvalidActorNameException when respawning after stop (name reservation during termination).
        ActorRef<PlayerSessionBehavior.Command> ref = context.spawnAnonymous(b);
        sessions.put(playerId, ref);
        context.watchWith(ref, new PlayerSessionTerminated(playerId, ref));
    }

    private Behavior<Command> onStreamClosed(StreamClosed s) {
        Set<Long> players = streamToPlayers.remove(s.streamId());
        if (players == null) {
            return Behaviors.same();
        }
        for (Long playerId : players) {
            releaseOneStream(playerId);
        }
        return Behaviors.same();
    }

    private void releaseOneStream(long playerId) {
        int cur = playerRefCount.getOrDefault(playerId, 0);
        if (cur <= 1) {
            playerRefCount.remove(playerId);
            ActorRef<PlayerSessionBehavior.Command> ref = sessions.remove(playerId);
            if (ref != null) {
                context.stop(ref);
            }
        } else {
            playerRefCount.put(playerId, cur - 1);
        }
    }

    /**
     * Child stopped or crashed: remove from all streams and refcount so the next inbound frame
     * can re-spawn cleanly.
     */
    private Behavior<Command> onGetPlayerSession(GetPlayerSession g) {
        ActorRef<PlayerSessionBehavior.Command> ref = sessions.get(g.playerId());
        g.replyTo().tell(Optional.ofNullable(ref));
        return Behaviors.same();
    }

    private Behavior<Command> onPlayerSessionTerminated(PlayerSessionTerminated t) {
        ActorRef<PlayerSessionBehavior.Command> cur = sessions.get(t.playerId());
        if (cur == null || !cur.equals(t.ref())) {
            return Behaviors.same();
        }
        sessions.remove(t.playerId());
        playerRefCount.remove(t.playerId());
        for (Set<Long> set : streamToPlayers.values()) {
            set.remove(t.playerId());
        }
        return Behaviors.same();
    }
}

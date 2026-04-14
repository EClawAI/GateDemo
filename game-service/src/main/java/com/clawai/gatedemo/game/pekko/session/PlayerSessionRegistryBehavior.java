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
 * 将入站流帧按 {@code playerId} 路由到 {@link PlayerSessionBehavior}；
 * 按流引用计数，无活跃流引用时停止对应会话。
 */
public final class PlayerSessionRegistryBehavior {

    public sealed interface Command permits RouteInbound, StreamClosed, PlayerSessionTerminated, GetPlayerSession {}

    public record RouteInbound(long streamId, long playerId, int messageId, int seq, byte[] body)
            implements Command {}

    public record StreamClosed(long streamId) implements Command {}

    /** 被 watch 的 {@link PlayerSessionBehavior} 停止时触发；携带 {@code ref} 以免误清已替换的新会话。 */
    public record PlayerSessionTerminated(long playerId, ActorRef<PlayerSessionBehavior.Command> ref)
            implements Command {}

    /** 解析当前在线会话 Actor，供跨 Actor Ask（如 City → Player 掠夺结算）。 */
    public record GetPlayerSession(long playerId, ActorRef<Optional<ActorRef<PlayerSessionBehavior.Command>>> replyTo)
            implements Command {}

    public static Behavior<Command> create(GameMessageDispatcher dispatcher, PlayerPlunderLedger plunderLedger) {
        return Behaviors.setup(ctx -> new PlayerSessionRegistryBehavior(ctx, dispatcher, plunderLedger).running());
    }

    private final ActorContext<Command> context;
    private final GameMessageDispatcher dispatcher;
    private final PlayerPlunderLedger plunderLedger;
    private final Map<Long, ActorRef<PlayerSessionBehavior.Command>> sessions = new HashMap<>();
    /** streamId → 该流上至少收到过一帧的 playerId 集合 */
    private final Map<Long, Set<Long>> streamToPlayers = new HashMap<>();
    /** playerId → 当前引用该玩家的不同流数量 */
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
        // 匿名 spawn，避免 stop 后立刻重建时因名称占用触发 InvalidActorNameException。
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

    /** 响应 GetPlayerSession：返回当前 playerId 对应的在线会话（无则 EMPTY）。 */
    private Behavior<Command> onGetPlayerSession(GetPlayerSession g) {
        ActorRef<PlayerSessionBehavior.Command> ref = sessions.get(g.playerId());
        g.replyTo().tell(Optional.ofNullable(ref));
        return Behaviors.same();
    }

    /**
     * 子 Actor 停止或崩溃：从会话表与所有流的占用中移除该玩家，使后续入站帧可重新 spawn。
     */
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

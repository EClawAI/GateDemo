package com.clawai.gatedemo.game.pekko.bridge;

import com.clawai.gatedemo.game.pekko.session.PlayerSessionRegistryBehavior;
import org.apache.pekko.actor.typed.ActorRef;
import org.apache.pekko.actor.typed.Behavior;
import org.apache.pekko.actor.typed.javadsl.Behaviors;

/**
 * 每个 Gate–Game {@code StreamCommunication} 双向流对应一个流入口 Actor：将上行帧转发给
 * {@link PlayerSessionRegistryBehavior}（不在 gRPC/Netty 回调线程上执行业务）。
 * <p>
 * 设计见 {@code openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/design.md} 与
 * {@code docs/backpressure-design.md}（HTTP/2 流控与应用层邮箱）。
 */
public final class StreamIngressBehavior {

    private StreamIngressBehavior() {}

    /** 流入口 Actor 的消息协议。 */
    public sealed interface Command permits InboundStreamFrame, Shutdown {}

    /** 上行帧，字段对应 {@link com.clawai.gatedemo.game.handler.GameMessageDispatcher#dispatch} 参数。 */
    public record InboundStreamFrame(long playerId, int messageId, int seq, byte[] body) implements Command {}

    /** gRPC 流结束时停止本 Actor。 */
    public record Shutdown() implements Command {}

    public static Behavior<Command> create(
            long streamId, ActorRef<PlayerSessionRegistryBehavior.Command> playerSessionRegistry) {
        return Behaviors.setup(ctx -> Behaviors.receive(Command.class)
                .onMessage(InboundStreamFrame.class, frame -> {
                    playerSessionRegistry.tell(
                            new PlayerSessionRegistryBehavior.RouteInbound(
                                    streamId,
                                    frame.playerId(),
                                    frame.messageId(),
                                    frame.seq(),
                                    frame.body()));
                    return Behaviors.same();
                })
                .onMessage(Shutdown.class, s -> {
                    playerSessionRegistry.tell(new PlayerSessionRegistryBehavior.StreamClosed(streamId));
                    return Behaviors.stopped();
                })
                .build());
    }
}

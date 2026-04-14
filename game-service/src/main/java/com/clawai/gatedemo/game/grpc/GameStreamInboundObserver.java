package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.pekko.bridge.StreamIngressBehavior;
import com.clawai.gatedemo.grpc.GameMessage;
import io.grpc.stub.StreamObserver;
import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;

/**
 * {@code StreamCommunication} 的 gRPC 入站半侧：{@link #onNext} 仅将消息入队到流入口 Actor，
 * 不在 Netty/gRPC 回调线程上调用 {@link GameMessageDispatcher}。
 * <p>
 * 流结束时发送 {@link StreamIngressBehavior.Shutdown} 并清理 dispatcher 的 sender。
 * 设计见 {@code openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/design.md} 与
 * {@code docs/backpressure-design.md}（HTTP/2 流控与应用层邮箱）。
 */
final class GameStreamInboundObserver implements StreamObserver<GameMessage> {

    private final ActorRef<StreamIngressBehavior.Command> ingress;
    private final StreamObserver<GameMessage> downstreamResponseObserver;
    private final GameMessageDispatcher dispatcher;
    private final Logger logger;

    GameStreamInboundObserver(
            ActorRef<StreamIngressBehavior.Command> ingress,
            StreamObserver<GameMessage> downstreamResponseObserver,
            GameMessageDispatcher dispatcher,
            Logger logger) {
        this.ingress = ingress;
        this.downstreamResponseObserver = downstreamResponseObserver;
        this.dispatcher = dispatcher;
        this.logger = logger;
    }

    @Override
    public void onNext(GameMessage request) {
        logger.debug("收到 Stream 消息: gateId={}, playerId={}, msgId={}",
                request.getGateId(), request.getPlayerId(), request.getMsgId());
        ingress.tell(new StreamIngressBehavior.InboundStreamFrame(
                request.getPlayerId(),
                request.getMsgId(),
                request.getSeq(),
                request.getBody().toByteArray()));
    }

    @Override
    public void onError(Throwable t) {
        logger.warn("⚠️ Stream通信断开：{} (客户端可能已断开)", t.getMessage());
        endStream();
    }

    @Override
    public void onCompleted() {
        logger.info("🔚 Gate端Stream通信完成");
        downstreamResponseObserver.onCompleted();
        endStream();
    }

    private void endStream() {
        ingress.tell(new StreamIngressBehavior.Shutdown());
        dispatcher.setSender(null);
    }
}

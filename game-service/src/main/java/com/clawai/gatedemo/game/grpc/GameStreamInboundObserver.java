package com.clawai.gatedemo.game.grpc;

import com.clawai.gatedemo.game.handler.GameMessageDispatcher;
import com.clawai.gatedemo.game.pekko.bridge.StreamIngressBehavior;
import com.clawai.gatedemo.grpc.GameMessage;
import io.grpc.stub.StreamObserver;
import org.apache.pekko.actor.typed.ActorRef;
import org.slf4j.Logger;

/**
 * gRPC inbound half of {@code StreamCommunication}: {@link #onNext} only enqueues to the
 * stream ingress actor — it does not call {@link GameMessageDispatcher} on the Netty/gRPC
 * callback thread.
 * <p>
 * On stream end, sends {@link StreamIngressBehavior.Shutdown} and clears the dispatcher sender.
 * See {@code openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/design.md} and
 * {@code docs/backpressure-design.md} (HTTP/2 flow control vs application mailbox).
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

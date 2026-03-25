package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.core.message.MessageSender;
import com.clawai.gatedemo.grpc.GameMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Game 服务下行发送实现。通过 gRPC 双向流 {@link StreamObserver} 回送 Gate，
 * 从 message 的 class simpleName 查 messageId，body 为 protobuf 二进制。
 */
public class GameMessageSender implements MessageSender {

    private static final Logger logger = LoggerFactory.getLogger(GameMessageSender.class);

    private final StreamObserver<GameMessage> responseObserver;
    private final String gateId;

    public GameMessageSender(StreamObserver<GameMessage> responseObserver, String gateId) {
        this.responseObserver = responseObserver;
        this.gateId = gateId;
    }

    @Override
    public void send(long playerId, MessageLite message) {
        int msgId = resolveMessageId(message);
        if (msgId == 0) return;

        try {
            GameMessage out = GameMessage.newBuilder()
                    .setGateId(gateId)
                    .setPlayerId(playerId)
                    .setMsgId(msgId)
                    .setSeq(0)
                    .setTimestamp(System.currentTimeMillis())
                    .setBody(ByteString.copyFrom(message.toByteArray()))
                    .build();
            responseObserver.onNext(out);
        } catch (Exception e) {
            logger.error("下行消息发送失败: playerId={}, msgId={}, error={}",
                    playerId, msgId, e.getMessage());
        }
    }

    @Override
    public void broadcast(int gameId, MessageLite message) {
        send(0, message);
    }

    private int resolveMessageId(MessageLite message) {
        String name = message.getClass().getSimpleName();
        int id = MessageRouteRegistry.getIdByName(name);
        if (id == 0) {
            logger.error("下行消息 {} 未在 MessageRouteRegistry 中注册", name);
        }
        return id;
    }
}

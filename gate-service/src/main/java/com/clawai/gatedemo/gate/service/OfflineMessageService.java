package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import com.clawai.gatedemo.proto.gate.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * 离线消息服务：上线时根据 Redis Stream 中堆积条数决定正常推送或触发重新登录流程。
 */
@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);

    private static final int THRESHOLD = 200;
    private static final int MSG_ID_RELOGIN = MessageRouteRegistry.getIdByName("ErrorResponse");

    private final MessageQueueProducer producer;
    private final PlayerService playerService;

    public OfflineMessageService(MessageQueueProducer producer, @Lazy PlayerService playerService) {
        this.producer = producer;
        this.playerService = playerService;
    }

    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    public void onPlayerOnline(Long playerId, io.netty.channel.Channel channel) {
        long messageCount = producer.getOfflineMessageCount(playerId);
        logger.info("Player {} online, offline messages: {}", playerId, messageCount);

        if (messageCount > THRESHOLD) {
            handleReloginMode(playerId);
        } else {
            handleNormalMode(playerId, channel);
        }
    }

    private void handleReloginMode(Long playerId) {
        logger.warn("Player {} offline messages exceed threshold {}, triggering relogin", playerId, THRESHOLD);
        producer.deleteOfflineMessages(playerId);
        sendReloginNotification(playerId);
    }

    private void sendReloginNotification(Long playerId) {
        ErrorResponse body = ErrorResponse.newBuilder()
                .setCode("RELOGIN_REQUIRED")
                .setMessage("offline_messages_exceeded, threshold=" + THRESHOLD)
                .build();

        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(MSG_ID_RELOGIN);
        msg.getHeader().setMode(MessageHeader.MODE_PUSH);
        msg.setBody(new RawMessageBody(body.toByteArray()));

        boolean sent = playerService.sendToPlayer(playerId, msg);
        if (sent) {
            logger.info("Sent relogin notification to player {}", playerId);
        } else {
            logger.warn("Failed to send relogin notification to player {}", playerId);
        }
    }

    private void handleNormalMode(Long playerId, io.netty.channel.Channel channel) {
        logger.debug("Pushing offline messages one by one to player {}", playerId);
    }
}

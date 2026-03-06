package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import com.clawai.gatedemo.gate.protocol.MessageIdRegistry;
import com.clawai.gatedemo.gate.protocol.model.JsonMessageBody;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);

    private int threshold = 200;

    private final MessageQueueProducer producer;
    private final ConnectionManager connectionManager;
    private final PlayerService playerService;

    public OfflineMessageService(MessageQueueProducer producer, ConnectionManager connectionManager, PlayerService playerService) {
        this.producer = producer;
        this.connectionManager = connectionManager;
        this.playerService = playerService;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    public void onPlayerOnline(Long playerId, Channel channel) {
        long messageCount = producer.getOfflineMessageCount(playerId);

        logger.info("Player {} online, offline messages: {}", playerId, messageCount);

        if (messageCount > threshold) {
            handleReloginMode(playerId);
        } else {
            handleNormalMode(playerId, channel);
        }
    }

    private void handleReloginMode(Long playerId) {
        logger.warn("Player {} offline messages {} exceed threshold, triggering relogin",
                playerId, threshold);

        producer.deleteOfflineMessages(playerId);

        sendReloginNotification(playerId);
    }

    private void sendReloginNotification(Long playerId) {
        WrappedMessage reloginMessage = new WrappedMessage();
        MessageHeader header = new MessageHeader();
        header.setMessageId(MessageIdRegistry.getIdByName("auth.relogin"));
        header.setMode(MessageHeader.MODE_PUSH);

        Map<String, Object> bodyData = new HashMap<>();
        bodyData.put("reason", "offline_messages_exceeded");
        bodyData.put("threshold", threshold);

        reloginMessage.setHeader(header);
        reloginMessage.setBody(new JsonMessageBody(bodyData));

        boolean sent = playerService.sendToPlayer(playerId, convertToPlayerMessage(reloginMessage));
        if (sent) {
            logger.info("Sent relogin notification to player {}", playerId);
        } else {
            logger.warn("Failed to send relogin notification to player {}", playerId);
        }
    }

    private void handleNormalMode(Long playerId, Channel channel) {
        logger.debug("Pushing offline messages one by one to player {}", playerId);
    }

    public void saveMessageForOfflinePlayer(Long playerId, short messageId, Map<String, Object> messageData) {
        producer.saveOfflineMessage(playerId, messageId, messageData);
        logger.debug("Saved offline message for player {}: msgId={}", playerId, messageId);
    }

    public long getOfflineMessageCount(Long playerId) {
        return producer.getOfflineMessageCount(playerId);
    }

    private com.clawai.gatedemo.gate.model.PlayerMessage convertToPlayerMessage(WrappedMessage wrapped) {
        com.clawai.gatedemo.gate.model.PlayerMessage msg = new com.clawai.gatedemo.gate.model.PlayerMessage();
        msg.setType("game_msg");
        msg.setMsgType(MessageIdRegistry.getNameById(wrapped.getHeader().getMessageId()));
        msg.setBody(wrapped.getBody() != null ? wrapped.getBody().getData() : new HashMap<>());
        return msg;
    }
}

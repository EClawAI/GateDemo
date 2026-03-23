package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import com.clawai.gatedemo.gate.protocol.MessageIdRegistry;
import com.clawai.gatedemo.gate.protocol.model.JsonMessageBody;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线消息服务 - 管理玩家离线时的消息存储与推送
 *
 * @see MessageQueueProducer Redis队列生产者
 */
@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);

    private static final int THRESHOLD = 200;

    private final MessageQueueProducer producer;
    private final PlayerService playerService;

    public OfflineMessageService(MessageQueueProducer producer, @Lazy PlayerService playerService) {
        this.producer = producer;
        this.playerService = playerService;
    }

    /**
     * 玩家离线回调
     */
    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    /**
     * 玩家上线回调：检查离线消息数量，决定推送或触发 relogin
     */
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
        WrappedMessage reloginMessage = new WrappedMessage();
        MessageHeader header = new MessageHeader();
        header.setMessageId(MessageIdRegistry.getIdByName("auth.relogin"));
        header.setMode(MessageHeader.MODE_PUSH);

        Map<String, Object> bodyData = new HashMap<>();
        bodyData.put("reason", "offline_messages_exceeded");
        bodyData.put("threshold", THRESHOLD);

        reloginMessage.setHeader(header);
        reloginMessage.setBody(new JsonMessageBody(bodyData));

        boolean sent = playerService.sendToPlayer(playerId, convertToPlayerMessage(reloginMessage));
        if (sent) {
            logger.info("Sent relogin notification to player {}", playerId);
        } else {
            logger.warn("Failed to send relogin notification to player {}", playerId);
        }
    }

    private void handleNormalMode(Long playerId, io.netty.channel.Channel channel) {
        logger.debug("Pushing offline messages one by one to player {}", playerId);
        // TODO: 实现逐条推送逻辑
    }

    private com.clawai.gatedemo.gate.model.PlayerMessage convertToPlayerMessage(WrappedMessage wrapped) {
        com.clawai.gatedemo.gate.model.PlayerMessage msg = new com.clawai.gatedemo.gate.model.PlayerMessage();
        msg.setType("game_msg");
        msg.setMsgType(MessageIdRegistry.getNameById(wrapped.getHeader().getMessageId()));
        msg.setBody(wrapped.getBody() != null ? wrapped.getBody().getData() : new HashMap<>());
        return msg;
    }
}

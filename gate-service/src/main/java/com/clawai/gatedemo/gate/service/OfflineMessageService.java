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
 * 离线消息服务：上线时根据 Redis Stream 中堆积条数决定正常推送或触发重新登录流程（与 {@link OfflineMessageConfig} 可对齐的常量阈值见 {@link #THRESHOLD}）。
 *
 * @see MessageQueueProducer Redis 队列生产者
 */
@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);

    /** 离线条数阈值，超过则清空离线流并通知客户端 relogin（当前为常量，可与配置统一） */
    private static final int THRESHOLD = 200;

    private final MessageQueueProducer producer;
    private final PlayerService playerService;

    /**
     * @param producer       查询/删除离线 Stream
     * @param playerService  延迟注入以避免与玩家服务的循环依赖
     */
    public OfflineMessageService(MessageQueueProducer producer, @Lazy PlayerService playerService) {
        this.producer = producer;
        this.playerService = playerService;
    }

    /**
     * 玩家断开时调用，当前仅记录日志，可扩展为标记离线状态等。
     */
    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    /**
     * 玩家上线时检查离线消息条数：低于阈值走普通模式（预留逐条推送），否则删流并下发 relogin 指令。
     *
     * @param channel 当前连接，普通模式下用于后续下行（TODO）
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

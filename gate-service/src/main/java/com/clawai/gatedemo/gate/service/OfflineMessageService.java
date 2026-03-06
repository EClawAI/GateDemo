package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import com.clawai.gatedemo.gate.protocol.MessageIdRegistry;
import com.clawai.gatedemo.gate.protocol.model.JsonMessageBody;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线消息服务 - 管理玩家离线时的消息存储与推送
 *
 * 设计原理：
 * 游戏中玩家会频繁离线/上线，需要一种机制确保消息不丢失。
 * 本服务使用Redis Stream存储离线消息，玩家上线时推送。
 *
 * 工作流程：
 * 1. 玩家A发送消息给玩家B
 * 2. Gate检查玩家B是否在线
 *    - 在线：直接推送
 *    - 离线：存入Redis Stream
 * 3. 玩家B上线
 * 4. Gate检查离线消息数量
 *    - ≤200条：逐条推送给玩家B
 *    - >200条：触发relogin，让玩家重新登录同步状态
 *
 * 阈值设计（200条）：
 * - 为什么是这个数字？
 *   200条消息约100KB数据，在网络不好时也能接受
 * - 超过阈值说明玩家离线太久
 *   游戏状态可能已过期，直接重置更合理
 *
 * 与其他服务的关系：
 * - MessageQueueProducer: 负责Redis Stream的读写
 * - ConnectionManager: 检查玩家在线状态
 * - PlayerService: 发送消息给玩家
 *
 * @see MessageQueueProducer Redis队列生产者
 */
@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);

    /** 离线消息阈值，超过此数量触发relogin */
    private int threshold = 200;

    /** Redis消息队列生产者 */
    private final MessageQueueProducer producer;

    /** 连接管理器 */
    private final ConnectionManager connectionManager;

    /** 玩家服务 */
    private final PlayerService playerService;

    /**
     * 构造函数
     *
     * 使用@Lazy解决循环依赖：
     * OfflineMessageService → PlayerService → OfflineMessageService
     */
    public OfflineMessageService(MessageQueueProducer producer, ConnectionManager connectionManager, 
                               @Lazy PlayerService playerService) {
        this.producer = producer;
        this.connectionManager = connectionManager;
        this.playerService = playerService;
    }

    /**
     * 设置离线消息阈值
     * @param threshold 阈值
     */
    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    /**
     * 玩家离线回调
     *
     * 调用时机：玩家断开连接时
     *
     * @param playerId 玩家ID
     */
    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    /**
     * 玩家上线回调
     *
     * 调用时机：玩家完成认证后
     *
     * 流程：
     * 1. 检查离线消息数量
     * 2. 根据数量决定处理方式
     *
     * @param playerId 玩家ID
     * @param channel 玩家Channel
     */
    public void onPlayerOnline(Long playerId, Channel channel) {
        long messageCount = producer.getOfflineMessageCount(playerId);

        logger.info("Player {} online, offline messages: {}", playerId, messageCount);

        if (messageCount > threshold) {
            // 超过阈值，触发relogin
            handleReloginMode(playerId);
        } else {
            // 正常模式，逐条推送
            handleNormalMode(playerId, channel);
        }
    }

    /**
     * 处理relogin模式
     *
     * 场景：玩家离线时间过长，积累消息过多
     *
     * 处理方式：
     * 1. 删除所有离线消息
     * 2. 发送relogin通知给玩家
     * 3. 玩家收到通知后重新登录
     *
     * 为什么这样做？
     * - 消息过多可能导致数据不一致
     * - 游戏状态已过期，需要重新同步
     * - 避免一次性推送大量消息导致客户端卡顿
     *
     * @param playerId 玩家ID
     */
    private void handleReloginMode(Long playerId) {
        logger.warn("Player {} offline messages {} exceed threshold, triggering relogin",
                playerId, threshold);

        // 删除离线消息
        producer.deleteOfflineMessages(playerId);

        // 发送relogin通知
        sendReloginNotification(playerId);
    }

    /**
     * 发送relogin通知
     *
     * 消息格式：
     * {
     *     "type": "game_msg",
     *     "msgType": "auth.relogin",
     *     "body": {
     *         "reason": "offline_messages_exceeded",
     *         "threshold": 200
     *     }
     * }
     *
     * @param playerId 玩家ID
     */
    private void sendReloginNotification(Long playerId) {
        // 构建消息
        WrappedMessage reloginMessage = new WrappedMessage();
        MessageHeader header = new MessageHeader();
        header.setMessageId(MessageIdRegistry.getIdByName("auth.relogin"));
        header.setMode(MessageHeader.MODE_PUSH);

        Map<String, Object> bodyData = new HashMap<>();
        bodyData.put("reason", "offline_messages_exceeded");
        bodyData.put("threshold", threshold);

        reloginMessage.setHeader(header);
        reloginMessage.setBody(new JsonMessageBody(bodyData));

        // 转换为PlayerMessage并发送
        boolean sent = playerService.sendToPlayer(playerId, convertToPlayerMessage(reloginMessage));
        if (sent) {
            logger.info("Sent relogin notification to player {}", playerId);
        } else {
            logger.warn("Failed to send relogin notification to player {}", playerId);
        }
    }

    /**
     * 处理正常模式
     *
     * 场景：玩家离线时间短，消息数量少
     *
     * 处理方式：
     * 逐条从Redis拉取离线消息
     * 发送给玩家
     *
     * @param playerId 玩家ID
     * @param channel 玩家Channel
     */
    private void handleNormalMode(Long playerId, Channel channel) {
        logger.debug("Pushing offline messages one by one to player {}", playerId);
        // TODO: 实现逐条推送逻辑
    }

    /**
     * 为离线玩家保存消息
     *
     * 调用时机：收到消息时，检查目标玩家不在线
     *
     * @param playerId 目标玩家ID
     * @param messageId 消息ID
     * @param messageData 消息内容
     */
    public void saveMessageForOfflinePlayer(Long playerId, short messageId, Map<String, Object> messageData) {
        producer.saveOfflineMessage(playerId, messageId, messageData);
        logger.debug("Saved offline message for player {}: msgId={}", playerId, messageId);
    }

    /**
     * 获取离线消息数量
     *
     * @param playerId 玩家ID
     * @return 消息数量
     */
    public long getOfflineMessageCount(Long playerId) {
        return producer.getOfflineMessageCount(playerId);
    }

    /**
     * 将WrappedMessage转换为PlayerMessage
     *
     * 这是内部方法，用于适配PlayerService的接口
     *
     * @param wrapped 包装消息
     * @return 玩家消息
     */
    private com.clawai.gatedemo.gate.model.PlayerMessage convertToPlayerMessage(WrappedMessage wrapped) {
        com.clawai.gatedemo.gate.model.PlayerMessage msg = new com.clawai.gatedemo.gate.model.PlayerMessage();
        msg.setType("game_msg");
        msg.setMsgType(MessageIdRegistry.getNameById(wrapped.getHeader().getMessageId()));
        msg.setBody(wrapped.getBody() != null ? wrapped.getBody().getData() : new HashMap<>());
        return msg;
    }
}

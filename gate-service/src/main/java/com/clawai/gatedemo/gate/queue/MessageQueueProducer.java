package com.clawai.gatedemo.gate.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 消息队列生产者 - 将消息发送到Redis Stream
 *
 * 设计原理：
 * Redis Stream是Redis 5.0引入的数据结构，类似于Kafka的消息队列。
 * 支持消息持久化、消费者组、消息ID等特性。
 *
 * 核心功能：
 * 1. sendToGame: 发送消息给游戏服（实时消息）
 * 2. saveOfflineMessage: 存储离线消息
 * 3. getOfflineMessageCount: 获取离线消息数量
 * 4. deleteOfflineMessages: 删除离线消息
 *
 * Redis Stream vs Redis Pub/Sub：
 * | 特性 | Stream | Pub/Sub |
 * |------|--------|---------|
 * | 消息持久化 | ✓ | ✗ |
 * | 消费者组 | ✓ | ✗ |
 * | 消息确认 | ✓ | ✗ |
 * | 消息回溯 | ✓ | ✗ |
 *
 * 为什么选择Stream而不是Pub/Sub？
 * 1. 消息持久化：网关重启时消息不丢失
 * 2. 消费者组：支持多Gate实例负载均衡
 * 3. 消息确认：确保消息被正确处理
 *
 * Stream Key设计：
 * - game:message:queue: Gate→Game的消息队列
 * - game:offline:messages:{playerId}: 玩家离线消息
 */
@Component
public class MessageQueueProducer {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueueProducer.class);

    /** Gate→Game 消息队列的Stream Key */
    private static final String STREAM_KEY = "game:message:queue";

    /** 离线消息的Stream Key前缀 */
    private static final String OFFLINE_STREAM_KEY = "game:offline:messages";

    /** Redis操作模板 */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 构造函数
     * @param redisTemplate Redis操作模板，Spring自动注入
     */
    public MessageQueueProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送消息给游戏服务
     *
     * 使用场景：
     * - 玩家发送聊天消息 → 转发给Game
     * - 玩家执行游戏操作 → 转发给Game
     *
     * 消息流程：
     * Gate → Redis Stream(game:message:queue) → Game
     *
     * @param playerId 玩家ID
     * @param messageId 消息ID
     * @param messageData 消息内容
     * @return 消息RecordId，发送失败返回null
     */
    public String sendToGame(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            // 构建消息内容
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

            // 使用Spring Data Redis的Stream API发送消息
            // Redis Stream会自动生成消息ID（格式：时间戳-序号）
            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(STREAM_KEY)
                    .ofMap(message));

            logger.debug("Message sent to game queue: playerId={}, msgId={}, recordId={}",
                    playerId, messageId, recordId);
            
            // 返回消息ID，便于追踪
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.error("Failed to send message to game queue: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 保存离线消息
     *
     * 使用场景：
     * - 玩家离线时，有其他玩家发送消息
     * - 玩家上线时拉取离线消息
     *
     * 消息存储结构：
     * Key: game:offline:messages:{playerId}
     * Value: {player_id, message_id, data, timestamp}
     *
     * @param playerId 目标玩家ID
     * @param messageId 消息ID
     * @param messageData 消息内容
     * @return 消息RecordId
     */
    public String saveOfflineMessage(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

            // 每个玩家一个独立的Stream
            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(getOfflineStreamKey(playerId))
                    .ofMap(message));

            logger.debug("Offline message saved: playerId={}, msgId={}, recordId={}",
                    playerId, messageId, recordId);
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.error("Failed to save offline message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成离线消息的Stream Key
     *
     * 设计：每个玩家一个Stream，便于隔离和管理
     * Key格式：game:offline:messages:12345
     *
     * @param playerId 玩家ID
     * @return Stream Key
     */
    private String getOfflineStreamKey(Long playerId) {
        return OFFLINE_STREAM_KEY + ":" + playerId;
    }

    /**
     * 获取离线消息数量
     *
     * 用于判断是否需要触发relogin
     *
     * @param playerId 玩家ID
     * @return 离线消息数量
     */
    public long getOfflineMessageCount(Long playerId) {
        try {
            return redisTemplate.opsForStream().size(getOfflineStreamKey(playerId));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 删除离线消息
     *
     * 使用场景：
     * 1. 玩家触发relogin时
     * 2. 玩家上线拉取完消息后
     *
     * @param playerId 玩家ID
     */
    public void deleteOfflineMessages(Long playerId) {
        try {
            String key = getOfflineStreamKey(playerId);
            redisTemplate.delete(key);
            logger.info("Deleted offline messages for player: {}", playerId);
        } catch (Exception e) {
            logger.error("Failed to delete offline messages: {}", e.getMessage());
        }
    }
}

package com.clawai.gatedemo.gate.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class MessageQueueProducer {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueueProducer.class);

    private static final String STREAM_KEY = "game:message:queue";
    private static final String OFFLINE_STREAM_KEY = "game:offline:messages";

    private final RedisTemplate<String, Object> redisTemplate;

    public MessageQueueProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public String sendToGame(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(STREAM_KEY)
                    .ofMap(message));

            logger.debug("Message sent to game queue: playerId={}, msgId={}, recordId={}",
                    playerId, messageId, recordId);
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.error("Failed to send message to game queue: {}", e.getMessage());
            return null;
        }
    }

    public String saveOfflineMessage(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

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

    private String getOfflineStreamKey(Long playerId) {
        return OFFLINE_STREAM_KEY + ":" + playerId;
    }

    public long getOfflineMessageCount(Long playerId) {
        try {
            return redisTemplate.opsForStream().size(getOfflineStreamKey(playerId));
        } catch (Exception e) {
            return 0;
        }
    }

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

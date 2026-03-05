package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import com.clawai.gatedemo.game.model.GameMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MessageSenderService {

    private static final Logger logger = LoggerFactory.getLogger(MessageSenderService.class);

    private final GameConfig gameConfig;
    private final ObjectMapper objectMapper;
    private final AtomicLong seqGenerator = new AtomicLong(1);

    // playerId -> pending messages queue (for offline message caching)
    private final ConcurrentMap<Long, LinkedBlockingQueue<Map<String, Object>>> playerMessageQueue = new ConcurrentHashMap<>();

    public MessageSenderService(GameConfig gameConfig, ObjectMapper objectMapper) {
        this.gameConfig = gameConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 向指定玩家发送消息
     * 无 Redis 版本：直接处理消息，如果玩家不在线则缓存
     */
    public void sendToPlayer(Long playerId, String msgType, Map<String, Object> body) {
        long seq = seqGenerator.getAndIncrement();
        
        Map<String, Object> message = Map.of(
            "playerId", playerId,
            "msgType", msgType,
            "seq", seq,
            "timestamp", System.currentTimeMillis(),
            "body", body
        );

        // 如果玩家在线，直接发送（由 GameMessageHandler 处理）
        // 如果玩家离线，缓存消息
        cacheMessageForPlayer(playerId, message);
        
        logger.info("Message queued for player {}, seq={}, type={}", playerId, seq, msgType);
    }

    /**
     * 缓存玩家消息
     */
    private void cacheMessageForPlayer(Long playerId, Map<String, Object> message) {
        playerMessageQueue.computeIfAbsent(playerId, k -> new LinkedBlockingQueue<>(gameConfig.getCache().getMaxSize()))
            .offer(message);
        
        // 简单的大小限制
        LinkedBlockingQueue<Map<String, Object>> queue = playerMessageQueue.get(playerId);
        if (queue != null && queue.size() > gameConfig.getCache().getMaxSize()) {
            queue.poll(); // 移除最旧的消息
        }
    }

    /**
     * 获取玩家的待处理消息队列
     */
    public LinkedBlockingQueue<Map<String, Object>> getPlayerMessageQueue(Long playerId) {
        return playerMessageQueue.get(playerId);
    }

    /**
     * 移除玩家的消息队列（玩家下线时调用）
     */
    public void removePlayerMessageQueue(Long playerId) {
        playerMessageQueue.remove(playerId);
    }

    /**
     * 发送测试消息（用于测试）
     */
    public void sendTestMessage(Long playerId) {
        Map<String, Object> body = Map.of(
            "content", "Hello from Game Service!",
            "message_id", UUID.randomUUID().toString()
        );
        sendToPlayer(playerId, "battle.update", body);
    }

    /**
     * 批量发送消息给多个玩家
     */
    public void broadcastToPlayers(Long[] playerIds, String msgType, Map<String, Object> body) {
        for (Long playerId : playerIds) {
            sendToPlayer(playerId, msgType, body);
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}

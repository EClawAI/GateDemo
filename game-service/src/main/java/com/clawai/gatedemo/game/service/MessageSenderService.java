package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import com.clawai.gatedemo.game.model.GameMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MessageSenderService {

    private static final Logger logger = LoggerFactory.getLogger(MessageSenderService.class);

    private final GameConfig gameConfig;
    private final RedisReactiveCommands<String, String> redisCommands;
    private final ObjectMapper objectMapper;
    private final AtomicLong seqGenerator = new AtomicLong(1);

    public MessageSenderService(GameConfig gameConfig, RedisReactiveCommands<String, String> redisCommands, ObjectMapper objectMapper) {
        this.gameConfig = gameConfig;
        this.redisCommands = redisCommands;
        this.objectMapper = objectMapper;
    }

    /**
     * 向指定玩家发送消息
     * 流程：
     * 1. 查询 player:gate:{playerId} 获取玩家所在 Gate
     * 2. 写入 stream:down:gate:{gateId}
     */
    public void sendToPlayer(Long playerId, String msgType, Map<String, Object> body) {
        String playerGateKey = "player:gate:" + playerId;
        
        redisCommands.get(playerGateKey)
            .flatMap(gateId -> {
                if (gateId == null) {
                    logger.warn("Player {} not found in any gate", playerId);
                    return redisCommands.dbsize().then(reactor.core.publisher.Mono.just("NOT_FOUND"));
                }
                
                String streamKey = "stream:down:gate:" + gateId;
                long seq = seqGenerator.getAndIncrement();
                
                Map<String, String> fields = Map.of(
                    "player_id", String.valueOf(playerId),
                    "msg_type", msgType,
                    "seq", String.valueOf(seq),
                    "timestamp", String.valueOf(System.currentTimeMillis()),
                    "body", toJson(body)
                );
                
                return redisCommands.xadd(streamKey, fields)
                    .doOnNext(msgId -> logger.info("Message sent to player {} via gate {}, msgId={}, seq={}", 
                        playerId, gateId, msgId, seq));
            })
            .subscribe();
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
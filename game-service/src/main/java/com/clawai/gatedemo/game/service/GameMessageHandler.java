package com.clawai.gatedemo.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class GameMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameMessageHandler.class);

    private final ObjectMapper objectMapper;

    public GameMessageHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void handleMessage(Long playerId, String msgType, String bodyStr) {
        try {
            Map<String, Object> body = objectMapper.readValue(bodyStr, Map.class);
            
            logger.debug("Handling message: playerId={}, msgType={}, body={}", playerId, msgType, body);
            
            // 根据消息类型处理
            switch (msgType) {
                case "battle.move":
                    handleBattleMove(playerId, body);
                    break;
                case "chat.message":
                    handleChatMessage(playerId, body);
                    break;
                default:
                    logger.debug("Unknown message type: {}", msgType);
            }
        } catch (Exception e) {
            logger.error("Error handling message: {}", e.getMessage());
        }
    }

    private void handleBattleMove(Long playerId, Map<String, Object> body) {
        logger.info("Battle move from player {}: {}", playerId, body);
    }

    private void handleChatMessage(Long playerId, Map<String, Object> body) {
        logger.info("Chat message from player {}: {}", playerId, body);
    }
}
package com.clawai.gatedemo.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UpstreamConsumerService {

    private static final Logger logger = LoggerFactory.getLogger(UpstreamConsumerService.class);

    private final ObjectMapper objectMapper;
    private final GameMessageHandler messageHandler;

    public UpstreamConsumerService(ObjectMapper objectMapper, GameMessageHandler messageHandler) {
        this.objectMapper = objectMapper;
        this.messageHandler = messageHandler;
    }

    /**
     * 处理来自 Gate 的直接消息
     * 无 Redis 版本：直接调用 messageHandler 处理
     */
    public void processMessage(String gateId, Long playerId, Integer gameId, 
                               String msgType, Long seq, Long timestamp, Map<String, Object> body) {
        try {
            logger.info("Received message from player {} via gate {}: {}", playerId, gateId, msgType);

            // 处理消息
            messageHandler.handleMessage(playerId, msgType, objectMapper.writeValueAsString(body));

            logger.debug("Message processed: playerId={}, gateId={}, seq={}", playerId, gateId, seq);
        } catch (Exception e) {
            logger.error("Error processing message: {}", e.getMessage());
        }
    }
}

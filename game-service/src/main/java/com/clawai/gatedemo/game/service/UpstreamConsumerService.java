package com.clawai.gatedemo.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 适配层：将网关转发的 Map 形态上行消息转为 JSON 串后交给 {@link GameMessageHandler}，使 Game 逻辑与 Gate 传输格式解耦。
 */
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
     * 将 Map 形态 body 序列化为 JSON 后走 {@link GameMessageHandler#handleMessage(Long, String, String)}（Unary，无 sink）。
     *
     * @param gateId    网关标识，仅日志
     * @param playerId  玩家 ID
     * @param gameId    当前未传入 Handler（内部按 0 处理），预留扩展
     * @param msgType   消息类型
     * @param seq       序列号，当前 Unary 路径未使用
     * @param timestamp 上游时间戳，当前未使用
     * @param body      业务负载 Map
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

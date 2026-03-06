package com.clawai.gatedemo.game.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 游戏消息处理器
 * 
 * 功能说明：
 * 1. 处理来自 Gate 服务的游戏消息
 * 2. 支持 HTTP 和 gRPC 两种调用方式
 * 3. 根据消息类型分发到具体处理逻辑
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Service
public class GameMessageHandler {

    private static final Logger logger = LoggerFactory.getLogger(GameMessageHandler.class);

    private final ObjectMapper objectMapper;

    public GameMessageHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 处理游戏消息（gRPC 调用）
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param msgType 消息类型
     * @param seq 消息序列号
     * @param bodyStr 消息体（JSON 字符串）
     */
    public void handleGameMessage(Long playerId, Integer gameId, String msgType, int seq, String bodyStr) {
        try {
            Map<String, Object> body = objectMapper.readValue(bodyStr, Map.class);
            
            logger.info("🎮 收到游戏消息：playerId={}, gameId={}, msgType={}, seq={}", 
                playerId, gameId, msgType, seq);
            
            // 根据消息类型处理
            switch (msgType) {
                case "battle.move":
                    handleBattleMove(playerId, gameId, body);
                    break;
                case "chat.message":
                    handleChatMessage(playerId, gameId, body);
                    break;
                default:
                    logger.debug("⚠️ 未知消息类型：{}", msgType);
            }
        } catch (Exception e) {
            logger.error("❌ 处理游戏消息失败：{}", e.getMessage());
            throw new RuntimeException("处理游戏消息失败", e);
        }
    }

    /**
     * 处理游戏消息（HTTP 调用，兼容旧版本）
     * 
     * @param playerId 玩家 ID
     * @param msgType 消息类型
     * @param bodyStr 消息体（JSON 字符串）
     */
    public void handleMessage(Long playerId, String msgType, String bodyStr) {
        handleGameMessage(playerId, 0, msgType, 0, bodyStr);
    }

    /**
     * 处理战斗移动消息
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param body 消息体
     */
    private void handleBattleMove(Long playerId, Integer gameId, Map<String, Object> body) {
        logger.info("⚔️ 战斗移动：playerId={}, gameId={}, move={}", playerId, gameId, body);
        // TODO: 实现战斗逻辑
    }

    /**
     * 处理聊天消息
     * 
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param body 消息体
     */
    private void handleChatMessage(Long playerId, Integer gameId, Map<String, Object> body) {
        logger.info("💬 聊天消息：playerId={}, gameId={}, message={}", playerId, gameId, body);
        // TODO: 实现聊天逻辑
    }
}
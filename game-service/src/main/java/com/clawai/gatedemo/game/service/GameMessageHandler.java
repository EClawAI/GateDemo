package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.model.PlayerData;
import com.clawai.gatedemo.game.persistence.PlayerDataManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
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
    private final PlayerDataManager playerDataManager;

    public GameMessageHandler(ObjectMapper objectMapper, PlayerDataManager playerDataManager) {
        this.objectMapper = objectMapper;
        this.playerDataManager = playerDataManager;
    }

    /**
     * 处理游戏消息（无 sink，适用于 unary RPC，echo/broadcast 不生效）
     */
    public void handleGameMessage(Long playerId, Integer gameId, String msgType, int seq, com.google.protobuf.ByteString bodyBytes) {
        handleGameMessage(playerId, gameId, msgType, seq, bodyBytes, null);
    }

    /**
     * 处理游戏消息
     *
     * @param playerId 玩家 ID
     * @param gameId 游戏 ID
     * @param msgType 消息类型
     * @param seq 消息序列号
     * @param bodyBytes 消息体（二进制Protobuf数据）
     * @param sink 可选，用于 echo/broadcast 时发送回 Gate（stream 路径）
     */
    public void handleGameMessage(Long playerId, Integer gameId, String msgType, int seq,
                                  com.google.protobuf.ByteString bodyBytes, OutgoingMessageSink sink) {
        try {
            String bodyStr = bodyBytes.toStringUtf8();
            Map<String, Object> body = objectMapper.readValue(bodyStr, Map.class);

            logger.info("🎮 收到游戏消息：playerId={}, gameId={}, msgType={}, seq={}",
                playerId, gameId, msgType, seq);

            switch (msgType) {
                case "player.login":
                    handlePlayerLogin(playerId, gameId, seq, body, sink);
                    break;
                case "player.save":
                    handlePlayerSave(playerId, gameId, seq, sink);
                    break;
                case "echo":
                    handleEcho(playerId, gameId, seq, body, sink);
                    break;
                case "broadcast":
                    handleBroadcast(playerId, gameId, seq, body, sink);
                    break;
                case "battle.move":
                    handleBattleMove(playerId, gameId, body, sink);
                    break;
                case "chat.message":
                    handleChatMessage(playerId, gameId, body, sink);
                    break;
                default:
                    logger.debug("⚠️ 未知消息类型：{}", msgType);
            }
        } catch (Exception e) {
            logger.error("❌ 处理游戏消息失败：{}", e.getMessage());
            throw new RuntimeException("处理游戏消息失败", e);
        }
    }

    private void handlePlayerLogin(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        PlayerData player = playerDataManager.load(playerId);

        playerDataManager.update(playerId, p -> {
            p.setLastLoginTime(LocalDateTime.now());
            p.setLoginCount(p.getLoginCount() + 1);
        });
        playerDataManager.saveNow(playerId);

        logger.info("Player login: playerId={}, level={}, loginCount={}", playerId, player.getLevel(), player.getLoginCount());

        if (sink != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("playerId", player.getId());
            response.put("nickname", player.getNickname());
            response.put("level", player.getLevel());
            response.put("gold", player.getGold());
            response.put("diamond", player.getDiamond());
            response.put("loginCount", player.getLoginCount());
            sink.emit(playerId, gameId != null ? gameId : 0, "player.login", seq, response);
        }
    }

    private void handlePlayerSave(Long playerId, Integer gameId, int seq, OutgoingMessageSink sink) {
        playerDataManager.saveNow(playerId);
        logger.info("Player data saved: playerId={}", playerId);

        if (sink != null) {
            Map<String, Object> response = new HashMap<>();
            response.put("playerId", playerId);
            response.put("result", "ok");
            sink.emit(playerId, gameId != null ? gameId : 0, "player.save", seq, response);
        }
    }

    /** echo: 原样回发给发送者 */
    private void handleEcho(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        if (sink == null) {
            logger.debug("echo 消息（无 sink，unary 路径不回发）");
            return;
        }
        sink.emit(playerId != null ? playerId : 0, gameId != null ? gameId : 0, "echo", seq, body);
        logger.info("📤 echo 回发：playerId={}, gameId={}", playerId, gameId);
    }

    /** broadcast: 转发给所有在线玩家，playerId=0 表示广播 */
    private void handleBroadcast(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        if (sink == null) {
            logger.debug("broadcast 消息（无 sink，unary 路径不广播）");
            return;
        }
        sink.emit(0, gameId != null ? gameId : 0, "broadcast", seq, body);
        logger.info("📤 broadcast 广播：gameId={}, from playerId={}", gameId, playerId);
    }

    /**
     * 处理游戏消息（HTTP 调用，兼容旧版本）
     * 
     * @param playerId 玩家 ID
     * @param msgType 消息类型
     * @param bodyStr 消息体（JSON 字符串）
     */
    public void handleMessage(Long playerId, String msgType, String bodyStr) {
        // 将字符串转换为ByteString
        com.google.protobuf.ByteString bodyBytes = com.google.protobuf.ByteString.copyFromUtf8(bodyStr);
        handleGameMessage(playerId, 0, msgType, 0, bodyBytes);
    }

    /**
     * 处理战斗移动消息：解析坐标并 broadcast 给同游戏内所有玩家
     */
    private void handleBattleMove(Long playerId, Integer gameId, Map<String, Object> body, OutgoingMessageSink sink) {
        logger.info("⚔️ 战斗移动：playerId={}, gameId={}, move={}", playerId, gameId, body);
        if (sink != null) {
            sink.emit(0, gameId != null ? gameId : 0, "battle.move", 0, body);
        }
    }

    /**
     * 处理聊天消息：解析内容并 broadcast 给同游戏内所有玩家
     */
    private void handleChatMessage(Long playerId, Integer gameId, Map<String, Object> body, OutgoingMessageSink sink) {
        logger.info("💬 聊天消息：playerId={}, gameId={}, message={}", playerId, gameId, body);
        if (sink != null) {
            sink.emit(0, gameId != null ? gameId : 0, "chat.message", 0, body);
        }
    }
}
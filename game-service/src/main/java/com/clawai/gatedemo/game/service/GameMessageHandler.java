package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.model.PlayerData;
import com.clawai.gatedemo.game.persistence.PlayerDataManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 游戏业务消息入口：将 Protobuf body 解析为 JSON Map，按 {@code msgType} 分发；流式场景通过 {@link OutgoingMessageSink} 回推 Gate。
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
     * Unary 路径：等价于 {@code sink == null} 的 {@link #handleGameMessage(Long, Integer, String, int, com.google.protobuf.ByteString, OutgoingMessageSink)}，
     * echo/broadcast 等依赖回推的消息类型不会下发到 Gate。
     *
     * @param playerId  玩家 ID
     * @param gameId    逻辑游戏 ID
     * @param msgType   业务消息类型键
     * @param seq       客户端序列号，回包时原样带回
     * @param bodyBytes UTF-8 JSON 字符串的 Protobuf 封装
     */
    public void handleGameMessage(Long playerId, Integer gameId, String msgType, int seq, com.google.protobuf.ByteString bodyBytes) {
        handleGameMessage(playerId, gameId, msgType, seq, bodyBytes, null);
    }

    /**
     * 解析 body 为 Map 后 switch 分发；失败时包装为 {@link RuntimeException} 抛出。若 {@code sink != null}，部分类型会通过 sink 异步回写 Gate。
     *
     * @param playerId  玩家 ID
     * @param gameId    游戏 ID（广播等场景写入回包）
     * @param msgType   消息类型
     * @param seq       序列号
     * @param bodyBytes 消息体
     * @param sink      可选下行出口；为 null 时 echo/broadcast 等仅打日志
     * @throws RuntimeException 解析或业务处理异常时
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

    /**
     * 登录：{@code load} 玩家、更新最近登录时间与登录次数、{@code saveNow} 落库；若存在 sink 则回发档案摘要。
     *
     * @param body 当前实现未读取，预留扩展
     */
    private void handlePlayerLogin(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        PlayerData player = playerDataManager.load(playerId);

        playerDataManager.update(playerId, p -> {
            p.setLastLoginTime(System.currentTimeMillis());
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

    /**
     * 主动存盘：对缓存中该玩家执行 {@code saveNow}；有 sink 时回 ACK。
     */
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

    /**
     * 将 body 原样经 sink 回给指定玩家；无 sink（Unary）时不回发。
     */
    private void handleEcho(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        if (sink == null) {
            logger.debug("echo 消息（无 sink，unary 路径不回发）");
            return;
        }
        sink.emit(playerId != null ? playerId : 0, gameId != null ? gameId : 0, "echo", seq, body);
        logger.info("📤 echo 回发：playerId={}, gameId={}", playerId, gameId);
    }

    /**
     * 以 {@code playerId=0} 语义触发广播，由 Gate 解释为同游戏全量推送。
     */
    private void handleBroadcast(Long playerId, Integer gameId, int seq, Map<String, Object> body, OutgoingMessageSink sink) {
        if (sink == null) {
            logger.debug("broadcast 消息（无 sink，unary 路径不广播）");
            return;
        }
        sink.emit(0, gameId != null ? gameId : 0, "broadcast", seq, body);
        logger.info("📤 broadcast 广播：gameId={}, from playerId={}", gameId, playerId);
    }

    /**
     * HTTP/Map 适配入口：将 JSON 字符串封成 {@link com.google.protobuf.ByteString}，{@code gameId=0}、{@code seq=0} 调用 Unary 处理链。
     *
     * @param playerId 玩家 ID
     * @param msgType  消息类型
     * @param bodyStr  已是 JSON 文本
     */
    public void handleMessage(Long playerId, String msgType, String bodyStr) {
        // 将字符串转换为ByteString
        com.google.protobuf.ByteString bodyBytes = com.google.protobuf.ByteString.copyFromUtf8(bodyStr);
        handleGameMessage(playerId, 0, msgType, 0, bodyBytes);
    }

    /**
     * 战斗移动：记录日志并以 {@code battle.move}、seq=0 广播 body（依赖 sink）。
     */
    private void handleBattleMove(Long playerId, Integer gameId, Map<String, Object> body, OutgoingMessageSink sink) {
        logger.info("⚔️ 战斗移动：playerId={}, gameId={}, move={}", playerId, gameId, body);
        if (sink != null) {
            sink.emit(0, gameId != null ? gameId : 0, "battle.move", 0, body);
        }
    }

    /**
     * 聊天：同战斗移动，以 {@code chat.message} 广播。
     */
    private void handleChatMessage(Long playerId, Integer gameId, Map<String, Object> body, OutgoingMessageSink sink) {
        logger.info("💬 聊天消息：playerId={}, gameId={}, message={}", playerId, gameId, body);
        if (sink != null) {
            sink.emit(0, gameId != null ? gameId : 0, "chat.message", 0, body);
        }
    }
}
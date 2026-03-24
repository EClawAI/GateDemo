package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.grpc.GameMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * 配置 gRPC 流式回调：将 Game 经双向流回推的消息（单播 echo / 广播）转为 {@link PlayerMessage} 并下发给 WebSocket 玩家。
 */
@Configuration
public class GrpcStreamConfig {

    private static final Logger logger = LoggerFactory.getLogger(GrpcStreamConfig.class);

    /** 与 Game 的 gRPC 连接池，在此注册 stream 消息处理器 */
    private final GameGrpcClientPool pool;
    /** 在线玩家与下行发送 */
    private final PlayerService playerService;
    /** 将 Game 消息体 JSON 反序列化为 Map */
    private final ObjectMapper objectMapper;

    /**
     * @param pool           用于设置流回调的客户端池
     * @param playerService  向指定玩家或全量在线玩家推送
     * @param objectMapper   解析回推 body 字符串
     */
    public GrpcStreamConfig(GameGrpcClientPool pool, PlayerService playerService, ObjectMapper objectMapper) {
        this.pool = pool;
        this.playerService = playerService;
        this.objectMapper = objectMapper;
    }

    /**
     * 向连接池注册流消息处理器：playerId 大于 0 时单播，否则遍历在线玩家广播；解析失败仅打日志，不抛出。
     */
    @PostConstruct
    public void init() {
        pool.setStreamMessageHandler(message -> {
            try {
                long playerId = message.getPlayerId();
                String bodyStr = message.getBody().toStringUtf8();
                @SuppressWarnings("unchecked")
                Map<String, Object> body = objectMapper.readValue(bodyStr, Map.class);

                PlayerMessage pm = new PlayerMessage();
                pm.setType("game_msg");
                pm.setMsgType(message.getMsgType());
                pm.setGameId(message.getGameId());
                pm.setSeq((long) message.getSeq());
                pm.setBody(body);
                pm.setTimestamp(message.getTimestamp());

                if (playerId > 0) {
                    pm.setPlayerId(playerId);
                    playerService.sendToPlayer(playerId, pm);
                    logger.debug("📤 echo 发送给玩家: playerId={}, msgType={}", playerId, message.getMsgType());
                } else {
                    for (Long pid : playerService.getAllOnlinePlayerIds()) {
                        pm.setPlayerId(pid);
                        playerService.sendToPlayer(pid, pm);
                    }
                    logger.debug("📤 broadcast 发送给 {} 个玩家: msgType={}", playerService.getOnlineCount(), message.getMsgType());
                }
            } catch (JsonProcessingException e) {
                logger.error("❌ 解析 Game 回推消息失败: {}", e.getMessage());
            }
        });
    }
}

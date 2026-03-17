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
 * Configures gRPC stream message handling: forwards Game responses (echo/broadcast) to players.
 */
@Configuration
public class GrpcStreamConfig {

    private static final Logger logger = LoggerFactory.getLogger(GrpcStreamConfig.class);

    private final GameGrpcClientPool pool;
    private final PlayerService playerService;
    private final ObjectMapper objectMapper;

    public GrpcStreamConfig(GameGrpcClientPool pool, PlayerService playerService, ObjectMapper objectMapper) {
        this.pool = pool;
        this.playerService = playerService;
        this.objectMapper = objectMapper;
    }

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

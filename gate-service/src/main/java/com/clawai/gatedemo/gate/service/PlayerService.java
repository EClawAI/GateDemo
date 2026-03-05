package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.reactive.RedisReactiveCommands;
import io.lettuce.core.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    private final GateConfig gateConfig;
    private final RedisReactiveCommands<String, String> redisCommands;
    private final ObjectMapper objectMapper;

    // player_id -> WebSocketSession
    private final Map<Long, WebSocketSession> players = new ConcurrentHashMap<>();

    public PlayerService(GateConfig gateConfig, RedisReactiveCommands<String, String> redisCommands, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.redisCommands = redisCommands;
        this.objectMapper = objectMapper;
    }

    public void registerPlayer(Long playerId, WebSocketSession session) {
        players.put(playerId, session);
        String key = "player:gate:" + playerId;
        redisCommands.setex(key, gateConfig.getPlayer().getMapTtl(), gateConfig.getId())
            .subscribe(result -> logger.info("Player {} registered to {}", playerId, gateConfig.getId()));
    }

    public void unregisterPlayer(Long playerId) {
        players.remove(playerId);
        String key = "player:gate:" + playerId;
        redisCommands.del(key).subscribe();
        logger.info("Player {} unregistered from {}", playerId, gateConfig.getId());
    }

    public WebSocketSession getPlayerSession(Long playerId) {
        return players.get(playerId);
    }

    public boolean hasPlayer(Long playerId) {
        return players.containsKey(playerId);
    }

    public void renewHeartbeat(Long playerId) {
        String key = "player:gate:" + playerId;
        redisCommands.expire(key, gateConfig.getPlayer().getMapTtl()).subscribe();
        logger.debug("Player {} heartbeat renewed", playerId);
    }

    public boolean sendToPlayer(Long playerId, PlayerMessage message) {
        WebSocketSession session = players.get(playerId);
        if (session != null && session.isOpen()) {
            try {
                String json = objectMapper.writeValueAsString(message);
                session.sendMessage(new TextMessage(json));
                logger.debug("Message sent to player {}: {}", playerId, message.getMsgType());
                return true;
            } catch (Exception e) {
                logger.error("Failed to send to player {}: {}", playerId, e.getMessage());
                return false;
            }
        } else {
            logger.warn("Player {} not connected to this gate", playerId);
            return false;
        }
    }

    public void forwardToGame(Long playerId, Integer gameId, PlayerMessage message) {
        String streamKey = "stream:up:game:" + gameId;
        Map<String, String> fields = Map.of(
            "gate_id", gateConfig.getId(),
            "player_id", String.valueOf(playerId),
            "msg_type", message.getMsgType() != null ? message.getMsgType() : "unknown",
            "seq", String.valueOf(message.getSeq() != null ? message.getSeq() : 0),
            "timestamp", String.valueOf(System.currentTimeMillis()),
            "body", toJson(message.getBody())
        );
        redisCommands.xadd(streamKey, fields).subscribe();
        logger.debug("Message forwarded to game {} from player {}", gameId, playerId);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    public int getOnlineCount() {
        return players.size();
    }
}
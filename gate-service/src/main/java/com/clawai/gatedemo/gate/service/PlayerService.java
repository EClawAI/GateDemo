package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.config.HttpClientConfig;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PlayerService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerService.class);

    private final GateConfig gateConfig;
    private final HttpClientConfig httpClientConfig;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // player_id -> WebSocketSession
    private final Map<Long, WebSocketSession> players = new ConcurrentHashMap<>();

    public PlayerService(GateConfig gateConfig, HttpClientConfig httpClientConfig, 
                         RestClient restClient, ObjectMapper objectMapper) {
        this.gateConfig = gateConfig;
        this.httpClientConfig = httpClientConfig;
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    public void registerPlayer(Long playerId, WebSocketSession session) {
        players.put(playerId, session);
        logger.info("Player {} registered to {}", playerId, gateConfig.getId());
    }

    public void unregisterPlayer(Long playerId) {
        players.remove(playerId);
        logger.info("Player {} unregistered from {}", playerId, gateConfig.getId());
    }

    public WebSocketSession getPlayerSession(Long playerId) {
        return players.get(playerId);
    }

    public boolean hasPlayer(Long playerId) {
        return players.containsKey(playerId);
    }

    public void renewHeartbeat(Long playerId) {
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
        String gameUrl = httpClientConfig.getGameBaseUrl() + "/api/game/receive";
        
        try {
            Map<String, Object> payload = Map.of(
                "gateId", gateConfig.getId(),
                "playerId", playerId,
                "gameId", gameId,
                "msgType", message.getMsgType() != null ? message.getMsgType() : "unknown",
                "seq", message.getSeq() != null ? message.getSeq() : 0,
                "timestamp", System.currentTimeMillis(),
                "body", message.getBody()
            );

            restClient.post()
                .uri(gameUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload)
                .retrieve()
                .toBodilessEntity();

            logger.debug("Message forwarded to game {} from player {}", gameId, playerId);
        } catch (Exception e) {
            logger.error("Failed to forward message to game: {}", e.getMessage());
        }
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

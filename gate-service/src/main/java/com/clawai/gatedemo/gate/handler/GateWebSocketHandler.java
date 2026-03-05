package com.clawai.gatedemo.gate.handler;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Component
public class GateWebSocketHandler extends TextWebSocketHandler {

    private static final Logger logger = LoggerFactory.getLogger(GateWebSocketHandler.class);

    private final PlayerService playerService;
    private final GateConfig gateConfig;
    private final ObjectMapper objectMapper;

    // playerId -> heartbeat future
    private final Map<Long, ScheduledFuture<?>> heartbeatTasks = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public GateWebSocketHandler(PlayerService playerService, GateConfig gateConfig, ObjectMapper objectMapper) {
        this.playerService = playerService;
        this.gateConfig = gateConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        logger.info("New WebSocket connection: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        PlayerMessage playerMessage = objectMapper.readValue(payload, PlayerMessage.class);

        String type = playerMessage.getType();
        logger.debug("Received message type: {}", type);

        if ("auth".equals(type)) {
            handleAuth(session, playerMessage);
        } else if ("heartbeat".equals(type)) {
            handleHeartbeat(session, playerMessage);
        } else if ("game_msg".equals(type)) {
            handleGameMessage(session, playerMessage);
        } else {
            logger.warn("Unknown message type: {}", type);
        }
    }

    private void handleAuth(WebSocketSession session, PlayerMessage message) throws IOException {
        Long playerId = message.getPlayerId();
        if (playerId == null || playerId <= 0) {
            session.close(new CloseStatus(1008, "Invalid player_id"));
            return;
        }

        // 检查是否已登录
        if (playerService.hasPlayer(playerId)) {
            logger.warn("Player {} already logged in", playerId);
            session.close(new CloseStatus(1008, "Player already logged in"));
            return;
        }

        // 注册玩家
        playerService.registerPlayer(playerId, session);

        // 启动心跳任务
        startHeartbeat(playerId);

        // 发送认证成功响应
        PlayerMessage response = new PlayerMessage();
        response.setType("auth_ack");
        response.setPlayerId(playerId);
        response.setTimestamp(System.currentTimeMillis());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));

        logger.info("Player {} authenticated successfully on {}", playerId, gateConfig.getId());
    }

    private void handleHeartbeat(WebSocketSession session, PlayerMessage message) throws IOException {
        Long playerId = message.getPlayerId();
        if (playerId != null) {
            playerService.renewHeartbeat(playerId);
            
            PlayerMessage response = new PlayerMessage();
            response.setType("heartbeat_ack");
            response.setPlayerId(playerId);
            response.setTimestamp(System.currentTimeMillis());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        }
    }

    private void handleGameMessage(WebSocketSession session, PlayerMessage message) throws IOException {
        Long playerId = message.getPlayerId();
        Integer gameId = message.getGameId();
        
        if (playerId != null && gameId != null) {
            playerService.forwardToGame(playerId, gameId, message);
            logger.debug("Game message forwarded: playerId={}, gameId={}", playerId, gameId);
        }
    }

    private void startHeartbeat(Long playerId) {
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            if (!playerService.hasPlayer(playerId)) {
                heartbeatTasks.get(playerId).cancel(false);
                heartbeatTasks.remove(playerId);
                return;
            }
            playerService.renewHeartbeat(playerId);
        }, gateConfig.getPlayer().getHeartbeatInterval(), 
           gateConfig.getPlayer().getHeartbeatInterval(), 
           TimeUnit.SECONDS);
        
        heartbeatTasks.put(playerId, future);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 找到对应的 playerId（从 session attributes 中获取）
        Long playerId = (Long) session.getAttributes().get("playerId");
        
        if (playerId != null) {
            // 取消心跳任务
            ScheduledFuture<?> future = heartbeatTasks.remove(playerId);
            if (future != null) {
                future.cancel(false);
            }
            
            // 注销玩家
            playerService.unregisterPlayer(playerId);
            logger.info("Player {} disconnected from {}", playerId, gateConfig.getId());
        }
        
        logger.info("WebSocket connection closed: {}, status: {}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        logger.error("WebSocket transport error: {}", exception.getMessage());
        session.close(CloseStatus.SERVER_ERROR);
    }
}
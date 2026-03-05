package com.clawai.gatedemo.game.controller;

import com.clawai.gatedemo.game.service.GameMessageHandler;
import com.clawai.gatedemo.game.service.MessageSenderService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private static final Logger logger = LoggerFactory.getLogger(GameController.class);

    private final MessageSenderService messageSenderService;
    private final GameMessageHandler messageHandler;
    private final ObjectMapper objectMapper;

    public GameController(MessageSenderService messageSenderService, GameMessageHandler messageHandler, 
                          ObjectMapper objectMapper) {
        this.messageSenderService = messageSenderService;
        this.messageHandler = messageHandler;
        this.objectMapper = objectMapper;
    }

    /**
     * 接收来自 Gate 的消息（无 Redis 版本）
     */
    @PostMapping("/receive")
    public ResponseEntity<Map<String, Object>> receiveFromGate(@RequestBody Map<String, Object> payload) {
        try {
            String gateId = (String) payload.get("gateId");
            Long playerId = ((Number) payload.get("playerId")).longValue();
            Integer gameId = (Integer) payload.get("gameId");
            String msgType = (String) payload.get("msgType");
            Long seq = ((Number) payload.get("seq")).longValue();
            Long timestamp = ((Number) payload.get("timestamp")).longValue();
            @SuppressWarnings("unchecked")
            Map<String, Object> body = (Map<String, Object>) payload.getOrDefault("body", Map.of());

            logger.info("Received message from gate {} player {}: type={}, seq={}", 
                gateId, playerId, msgType, seq);

            // 将 body 转换为 JSON 字符串
            String bodyStr = objectMapper.writeValueAsString(body);

            // 处理消息
            messageHandler.handleMessage(playerId, msgType, bodyStr);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "received", true
            ));
        } catch (JsonProcessingException e) {
            logger.error("Error processing message: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        } catch (Exception e) {
            logger.error("Error receiving message: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", e.getMessage()
            ));
        }
    }

    /**
     * 发送测试消息给指定玩家（通过 Gate 转发）
     */
    @PostMapping("/send/{playerId}")
    public Map<String, Object> sendTestMessage(@PathVariable Long playerId) {
        messageSenderService.sendTestMessage(playerId);
        return Map.of(
            "success", true,
            "message", "Test message sent to player " + playerId,
            "playerId", playerId
        );
    }

    /**
     * 发送自定义消息
     */
    @PostMapping("/send/{playerId}/custom")
    public Map<String, Object> sendCustomMessage(
            @PathVariable Long playerId,
            @RequestParam String msgType,
            @RequestBody Map<String, Object> body) {
        messageSenderService.sendToPlayer(playerId, msgType, body);
        return Map.of(
            "success", true,
            "message", "Message sent to player " + playerId,
            "playerId", playerId,
            "msgType", msgType
        );
    }

    /**
     * 健康检查
     */
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "UP",
            "service", "game-service"
        );
    }
}

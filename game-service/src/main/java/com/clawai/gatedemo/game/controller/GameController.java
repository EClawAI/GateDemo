package com.clawai.gatedemo.game.controller;

import com.clawai.gatedemo.game.service.MessageSenderService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/game")
public class GameController {

    private final MessageSenderService messageSenderService;

    public GameController(MessageSenderService messageSenderService) {
        this.messageSenderService = messageSenderService;
    }

    /**
     * 发送测试消息给指定玩家
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
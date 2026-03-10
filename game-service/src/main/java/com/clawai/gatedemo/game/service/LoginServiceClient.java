package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class LoginServiceClient {

    private static final Logger logger = LoggerFactory.getLogger(LoginServiceClient.class);

    private final GameConfig gameConfig;
    private final RestTemplate restTemplate;

    public LoginServiceClient(GameConfig gameConfig) {
        this.gameConfig = gameConfig;
        this.restTemplate = new RestTemplate();
    }

    public void recordLogin(Long playerId, Integer gameId) {
        try {
            String url = String.format("http://%s:%d/api/v1/game/login-record",
                gameConfig.getLoginService().getHost(),
                gameConfig.getLoginService().getPort());

            LoginRecordRequest request = new LoginRecordRequest();
            request.setPlayerId(playerId);
            request.setGameId(gameId);

            restTemplate.postForObject(url, request, Void.class);

            logger.info("Login record sent: playerId={}, gameId={}", playerId, gameId);
        } catch (Exception e) {
            logger.warn("Failed to record login to LoginService: {}", e.getMessage());
        }
    }

    public static class LoginRecordRequest {
        private Long playerId;
        private Integer gameId;

        public Long getPlayerId() { return playerId; }
        public void setPlayerId(Long playerId) { this.playerId = playerId; }
        public Integer getGameId() { return gameId; }
        public void setGameId(Integer gameId) { this.gameId = gameId; }
    }
}

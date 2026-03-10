package com.clawai.gatedemo.login.service;

import com.clawai.gatedemo.login.config.LoginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class GameRouteService {

    private static final Logger logger = LoggerFactory.getLogger(GameRouteService.class);

    private static final String PLAYER_LOGIN_KEY_PREFIX = "player:login:";
    private static final long PLAYER_LOGIN_EXPIRE_SECONDS = 7 * 24 * 3600;

    private final RedisTemplate<String, Object> redisTemplate;
    private final LoginConfig loginConfig;
    private final GateService gateService;

    public GameRouteService(RedisTemplate<String, Object> redisTemplate, LoginConfig loginConfig, GateService gateService) {
        this.redisTemplate = redisTemplate;
        this.loginConfig = loginConfig;
        this.gateService = gateService;
    }

    public Integer getRecommendedGame() {
        List<LoginConfig.RecommendGameConfig> recommendGames = loginConfig.getRecommendGames();
        
        if (recommendGames == null || recommendGames.isEmpty()) {
            logger.warn("No recommend games configured");
            return null;
        }
        
        int minPriority = Integer.MAX_VALUE;
        LoginConfig.RecommendGameConfig recommended = null;
        
        for (LoginConfig.RecommendGameConfig game : recommendGames) {
            if (game.getPriority() < minPriority) {
                minPriority = game.getPriority();
                recommended = game;
            }
        }
        
        return recommended != null ? recommended.getGameId() : null;
    }

    public Integer getLastLoginGame(Long playerId) {
        String key = PLAYER_LOGIN_KEY_PREFIX + playerId;
        Object value = redisTemplate.opsForValue().get(key);
        
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException e) {
                logger.warn("Invalid player login record: {}", value);
            }
        }
        
        return null;
    }

    public void savePlayerLoginRecord(Long playerId, Integer gameId) {
        String key = PLAYER_LOGIN_KEY_PREFIX + playerId;
        redisTemplate.opsForValue().set(key, gameId.toString(), PLAYER_LOGIN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        logger.info("Player {} login record saved, gameId: {}", playerId, gameId);
    }

    public RouteResult route(Long playerId) {
        RouteResult result = new RouteResult();
        
        GateService.GateInstance gate = gateService.getAvailableGate();
        if (gate == null) {
            logger.warn("No available gate found");
            result.setCode(1);
            result.setMessage("No available gate");
            return result;
        }
        
        result.setGateId(gate.getGateId());
        result.setGateHost(gate.getHost());
        result.setGatePort(gate.getPort());
        
        Integer lastGameId = getLastLoginGame(playerId);
        
        if (lastGameId != null) {
            result.setGameId(lastGameId);
            result.setMessage("Use last login game");
            logger.info("Player {} routing to last game: {}", playerId, lastGameId);
        } else {
            Integer recommendedGameId = getRecommendedGame();
            if (recommendedGameId != null) {
                result.setGameId(recommendedGameId);
                result.setMessage("Use recommended game");
                logger.info("Player {} routing to recommended game: {}", playerId, recommendedGameId);
            } else {
                result.setCode(2);
                result.setMessage("No available game");
                logger.warn("Player {} no available game", playerId);
            }
        }
        
        result.setCode(0);
        return result;
    }

    public static class RouteResult {
        private int code;
        private String message;
        private String gateId;
        private String gateHost;
        private Integer gatePort;
        private Integer gameId;

        public int getCode() {
            return code;
        }

        public void setCode(int code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public String getGateId() {
            return gateId;
        }

        public void setGateId(String gateId) {
            this.gateId = gateId;
        }

        public String getGateHost() {
            return gateHost;
        }

        public void setGateHost(String gateHost) {
            this.gateHost = gateHost;
        }

        public Integer getGatePort() {
            return gatePort;
        }

        public void setGatePort(Integer gatePort) {
            this.gatePort = gatePort;
        }

        public Integer getGameId() {
            return gameId;
        }

        public void setGameId(Integer gameId) {
            this.gameId = gameId;
        }
    }
}

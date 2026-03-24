package com.clawai.gatedemo.login.service;

import com.clawai.gatedemo.login.config.LoginConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 登录路由领域服务：综合 Redis 中游戏开服状态、玩家上次游戏与配置推荐服，决策目标游戏并配合 {@link GateService} 绑定网关。
 */
@Service
public class GameRouteService {

    private static final Logger logger = LoggerFactory.getLogger(GameRouteService.class);

    private static final String PLAYER_LASTGAME_KEY_PREFIX = "player:lastgame:";
    private static final String GAME_STATUS_KEY_PREFIX = "game:status:";
    private static final long PLAYER_LOGIN_EXPIRE_SECONDS = 7 * 24 * 3600;

    private final RedisTemplate<String, Object> redisTemplate;
    private final LoginConfig loginConfig;
    private final GateService gateService;

    public GameRouteService(RedisTemplate<String, Object> redisTemplate, LoginConfig loginConfig, GateService gateService) {
        this.redisTemplate = redisTemplate;
        this.loginConfig = loginConfig;
        this.gateService = gateService;
    }

    /**
     * 游戏服生命周期状态，与 Redis 中写入的数值编码一致。
     */
    public enum GameStatus {
        /** 未开服或不可进 */
        NOT_STARTED(0),
        /** 已开服但尚未开放登录 */
        STARTED_NOT_LOGIN(1),
        /** 已开服且允许玩家登录 */
        STARTED_CAN_LOGIN(2);

        private final int value;

        GameStatus(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }

        /**
         * @param value Redis 或配置中的整型状态码
         * @return 匹配项，未知时视为 {@link #NOT_STARTED}
         */
        public static GameStatus fromValue(int value) {
            for (GameStatus status : values()) {
                if (status.value == value) {
                    return status;
                }
            }
            return NOT_STARTED;
        }
    }

    /**
     * 从 Redis 解析指定游戏的当前状态；异常或缺键时视为未开服。
     *
     * @param gameId 游戏 ID，{@code null} 时返回 {@link GameStatus#NOT_STARTED}
     * @return 解析后的枚举状态
     */
    public GameStatus getGameStatus(Integer gameId) {
        if (gameId == null) {
            return GameStatus.NOT_STARTED;
        }
        try {
            String key = GAME_STATUS_KEY_PREFIX + gameId;
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                String str = value.toString();
                int status = Integer.parseInt(str.split(":")[0]);
                return GameStatus.fromValue(status);
            }
        } catch (Exception e) {
            logger.warn("Failed to get game status: gameId={}, error={}", gameId, e.getMessage());
        }
        return GameStatus.NOT_STARTED;
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
        String key = PLAYER_LASTGAME_KEY_PREFIX + playerId;
        Object value = redisTemplate.opsForValue().get(key);
        
        if (value != null) {
            try {
                String str = value.toString();
                return Integer.parseInt(str.split(":")[0]);
            } catch (NumberFormatException e) {
                logger.warn("Invalid player login record: {}", value);
            }
        }
        
        return null;
    }

    /**
     * 写入玩家最近游戏并设置过期，供下次登录优先匹配。
     *
     * @param playerId 玩家 ID
     * @param gameId   本次进入的游戏 ID
     */
    public void savePlayerLoginRecord(Long playerId, Integer gameId) {
        String key = PLAYER_LASTGAME_KEY_PREFIX + playerId;
        String value = gameId + ":" + System.currentTimeMillis();
        redisTemplate.opsForValue().set(key, value, PLAYER_LOGIN_EXPIRE_SECONDS, TimeUnit.SECONDS);
        logger.info("Player {} login record saved, gameId: {}", playerId, gameId);
    }

    /**
     * 综合可用网关、玩家上次游戏与推荐服及游戏状态，决定目标游戏与网关；必要时标记换服提示。
     *
     * @param playerId 待路由的玩家 ID
     * @return {@code code=0} 表示成功并含网关与游戏信息；非 0 为业务错误（如无可用网关/游戏）
     */
    public RouteResult route(Long playerId) {
        RouteResult result = new RouteResult();
        
        GateService.GateInstance gate = gateService.getAvailableGate();
        if (gate == null) {
            logger.warn("No available gate found");
            result.setCode(1);
            result.setMessage("暂时没有可用的网关服务器");
            return result;
        }
        
        result.setGateId(gate.getGateId());
        result.setGateHost(gate.getHost());
        result.setGatePort(gate.getPort());
        
        Integer lastGameId = getLastLoginGame(playerId);
        
        if (lastGameId != null) {
            GameStatus lastGameStatus = getGameStatus(lastGameId);
            
            if (lastGameStatus == GameStatus.STARTED_CAN_LOGIN) {
                savePlayerLoginRecord(playerId, lastGameId);
                result.setGameId(lastGameId);
                result.setCode(0);
                result.setMessage("success");
                logger.info("Player {} routing to last game: {}", playerId, lastGameId);
                return result;
            } else {
                Integer recommendGameId = getRecommendedGame();
                if (recommendGameId != null) {
                    GameStatus recommendStatus = getGameStatus(recommendGameId);
                    if (recommendStatus == GameStatus.STARTED_CAN_LOGIN) {
                        savePlayerLoginRecord(playerId, recommendGameId);
                        result.setGameId(recommendGameId);
                        result.setCode(0);
                        result.setRedirect(true);
                        result.setRedirectMessage("上次登录服务器不可用，已为您切换到推荐服");
                        logger.info("Player {} redirect to recommended game: {}", playerId, recommendGameId);
                        return result;
                    }
                }
            }
        }
        
        Integer recommendGameId = getRecommendedGame();
        if (recommendGameId != null) {
            GameStatus recommendStatus = getGameStatus(recommendGameId);
            if (recommendStatus == GameStatus.STARTED_CAN_LOGIN) {
                savePlayerLoginRecord(playerId, recommendGameId);
                result.setGameId(recommendGameId);
                result.setCode(0);
                result.setMessage("success");
                logger.info("Player {} routing to recommended game: {}", playerId, recommendGameId);
                return result;
            }
        }
        
        result.setCode(1);
        result.setMessage("暂时没有可用的游戏服务器，请稍后重试");
        logger.warn("Player {} no available game", playerId);
        return result;
    }

    /** 单次登录路由的输出：业务码、网关端点、目标游戏及是否向玩家展示换服说明 */
    public static class RouteResult {
        private int code;
        private String message;
        private String gateId;
        private String gateHost;
        private Integer gatePort;
        private Integer gameId;
        private boolean redirect;
        private String redirectMessage;

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

        public boolean isRedirect() {
            return redirect;
        }

        public void setRedirect(boolean redirect) {
            this.redirect = redirect;
        }

        public String getRedirectMessage() {
            return redirectMessage;
        }

        public void setRedirectMessage(String redirectMessage) {
            this.redirectMessage = redirectMessage;
        }
    }
}

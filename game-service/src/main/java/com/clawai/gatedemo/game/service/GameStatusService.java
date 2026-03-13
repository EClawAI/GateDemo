package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Service
public class GameStatusService {

    private static final Logger logger = LoggerFactory.getLogger(GameStatusService.class);

    private static final String GAME_STATUS_KEY = "game:status:";
    private static final long KEY_EXPIRE_SECONDS = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final GameConfig gameConfig;
    private final GameRegistryService registryService;

    private GameStatus currentStatus = GameStatus.NOT_STARTED;
    private int onlinePlayerCount = 0;
    private boolean initialized = false;

    public GameStatusService(RedisTemplate<String, Object> redisTemplate, 
                           GameConfig gameConfig,
                           GameRegistryService registryService) {
        this.redisTemplate = redisTemplate;
        this.gameConfig = gameConfig;
        this.registryService = registryService;
    }

    @PostConstruct
    public void init() {
        logger.info("GameStatusService initialized, gameId: {}", gameConfig.getId());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("GameStatusService starting, gameId: {}", gameConfig.getId());
        initialized = true;
        setStatus(GameStatus.STARTED_NOT_LOGIN);
    }

    public void setStatus(GameStatus status) {
        GameStatus oldStatus = this.currentStatus;
        this.currentStatus = status;
        if (initialized) {
            syncStatusToRedis();
            if (oldStatus != status && registryService != null) {
                registryService.publishStatusUpdate(oldStatus.getValue(), status.getValue());
            }
        }
        logger.info("Game status changed to: {} - {}", status.getValue(), status.getDescription());
    }

    public GameStatus getStatus() {
        return currentStatus;
    }

    public void setOnlinePlayerCount(int count) {
        this.onlinePlayerCount = count;
    }

    public int getOnlinePlayerCount() {
        return onlinePlayerCount;
    }

    @Scheduled(fixedRateString = "${game.status.heartbeat-interval:30000}")
    public void syncStatusToRedis() {
        if (!initialized) {
            return;
        }
        try {
            String key = GAME_STATUS_KEY + parseGameId(gameConfig.getId());
            String value = String.format("%d:%d:%d",
                currentStatus.getValue(),
                onlinePlayerCount,
                System.currentTimeMillis());

            redisTemplate.opsForValue().set(key, value, KEY_EXPIRE_SECONDS, TimeUnit.SECONDS);
            logger.debug("Game status synced to Redis: gameId={}, status={}, online={}",
                gameConfig.getId(), currentStatus.getValue(), onlinePlayerCount);
        } catch (Exception e) {
            logger.warn("Failed to sync game status to Redis: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        logger.info("GameStatusService shutting down, setting status to NOT_STARTED");
        setStatus(GameStatus.NOT_STARTED);
    }

    private int parseGameId(String id) {
        try {
            return Integer.parseInt(id.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1001;
        }
    }
}

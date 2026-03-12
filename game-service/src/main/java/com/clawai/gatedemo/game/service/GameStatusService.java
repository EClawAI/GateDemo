package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

@Service
public class GameStatusService {

    private static final Logger logger = LoggerFactory.getLogger(GameStatusService.class);

    private static final String GAME_STATUS_KEY = "game:status:";
    private static final long KEY_EXPIRE_SECONDS = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final GameConfig gameConfig;

    private GameStatus currentStatus = GameStatus.NOT_STARTED;
    private int onlinePlayerCount = 0;
    private boolean initialized = false;

    public GameStatusService(RedisTemplate<String, Object> redisTemplate, GameConfig gameConfig) {
        this.redisTemplate = redisTemplate;
        this.gameConfig = gameConfig;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("GameStatusService initializing, gameId: {}", gameConfig.getId());
        initialized = true;
        setStatus(GameStatus.STARTED_NOT_LOGIN);
    }

    public void setStatus(GameStatus status) {
        this.currentStatus = status;
        if (initialized) {
            syncStatusToRedis();
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
            String key = GAME_STATUS_KEY + gameConfig.getId();
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
}

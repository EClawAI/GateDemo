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
public class GameRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(GameRegistryService.class);

    private static final String REGISTRY_KEY_PREFIX = "game:registry:";
    private static final String EVENT_CHANNEL = "game:events";

    private final RedisTemplate<String, Object> redisTemplate;
    private final GameConfig gameConfig;
    private final GameStatusService gameStatusService;

    private boolean initialized = false;

    public GameRegistryService(RedisTemplate<String, Object> redisTemplate, 
                               GameConfig gameConfig, 
                               GameStatusService gameStatusService) {
        this.redisTemplate = redisTemplate;
        this.gameConfig = gameConfig;
        this.gameStatusService = gameStatusService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!gameConfig.getRegistry().isEnabled()) {
            logger.info("Game registry is disabled");
            return;
        }
        initialized = true;
        register();
    }

    public void register() {
        if (!initialized || !gameConfig.getRegistry().isEnabled()) {
            return;
        }
        try {
            int gameId = parseGameId(gameConfig.getId());
            int status = gameStatusService.getStatus().getValue();
            long timestamp = System.currentTimeMillis();

            String value = String.format("%s:%d:%d:%d",
                gameConfig.getHost(),
                gameConfig.getPort(),
                status,
                timestamp);

            redisTemplate.opsForValue().set(
                REGISTRY_KEY_PREFIX + gameId,
                value,
                gameConfig.getRegistry().getTtlSeconds(),
                TimeUnit.SECONDS
            );

            redisTemplate.convertAndSend(EVENT_CHANNEL,
                String.format("REGISTER:%d:%s", gameId, value));

            logger.info("Game registered: gameId={}, host={}, port={}, status={}",
                gameId, gameConfig.getHost(), gameConfig.getPort(), status);
        } catch (Exception e) {
            logger.warn("Failed to register game: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void unregister() {
        if (!initialized || !gameConfig.getRegistry().isEnabled()) {
            return;
        }
        try {
            int gameId = parseGameId(gameConfig.getId());
            redisTemplate.delete(REGISTRY_KEY_PREFIX + gameId);
            redisTemplate.convertAndSend(EVENT_CHANNEL,
                String.format("UNREGISTER:%d", gameId));
            logger.info("Game unregistered: gameId={}", gameId);
        } catch (Exception e) {
            logger.warn("Failed to unregister game: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRateString = "${game.registry.heartbeat-interval:30000}")
    public void heartbeat() {
        if (!initialized || !gameConfig.getRegistry().isEnabled()) {
            return;
        }
        register();
    }

    public void publishStatusUpdate(int oldStatus, int newStatus) {
        if (!initialized || !gameConfig.getRegistry().isEnabled()) {
            return;
        }
        try {
            int gameId = parseGameId(gameConfig.getId());
            redisTemplate.convertAndSend(EVENT_CHANNEL,
                String.format("UPDATE:%d:%d", gameId, newStatus));
            logger.info("Game status update published: gameId={}, status={}", gameId, newStatus);
        } catch (Exception e) {
            logger.warn("Failed to publish status update: {}", e.getMessage());
        }
    }

    private int parseGameId(String id) {
        try {
            return Integer.parseInt(id.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1001;
        }
    }
}

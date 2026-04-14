package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * 维护当前 {@link GameStatus} 与在线人数，周期性同步到 Redis，并在状态变化时通知 {@link GameRegistryService} 发布事件。
 */
@Service
public class GameStatusService {

    private static final Logger logger = LoggerFactory.getLogger(GameStatusService.class);

    private static final String GAME_STATUS_KEY = "game:status:";
    /** 状态键 TTL（秒），需小于或等于心跳间隔以保证键不中断续期。 */
    private static final long KEY_EXPIRE_SECONDS = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final GameConfig gameConfig;
    private final GameRegistryService registryService;

    private GameStatus currentStatus = GameStatus.NOT_STARTED;
    /** 当前在线人数快照，与状态一并写入 Redis（具体由谁更新计数视上层调用）。 */
    private int onlinePlayerCount = 0;
    /** {@link ApplicationReadyEvent} 处理后置 true，此前 setStatus 不写 Redis。 */
    private boolean initialized = false;

    /**
     * @param redisTemplate    状态键读写
     * @param gameConfig       解析 gameId 等
     * @param registryService  状态变化时发布 UPDATE（可为 null 则跳过）
     */
    public GameStatusService(
            RedisTemplate<String, Object> redisTemplate,
            GameConfig gameConfig,
            @Lazy GameRegistryService registryService) {
        this.redisTemplate = redisTemplate;
        this.gameConfig = gameConfig;
        this.registryService = registryService;
    }

    /**
     * Bean 创建后打日志；真正写 Redis 在 {@link #onApplicationReady()} 之后。
     */
    @PostConstruct
    public void init() {
        logger.info("GameStatusService initialized, gameId: {}", gameConfig.getId());
    }

    /**
     * 应用就绪后标记可同步，并将状态设为「已启动不可登录」直至 {@link GameServiceApplication} 再改为可登录。
     */
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

    /**
     * @return 当前内存中的游戏服状态
     */
    public GameStatus getStatus() {
        return currentStatus;
    }

    /**
     * @param count 在线人数，下次同步 Redis 时写入
     */
    public void setOnlinePlayerCount(int count) {
        this.onlinePlayerCount = count;
    }

    /**
     * @return 最近一次设置的在线人数
     */
    public int getOnlinePlayerCount() {
        return onlinePlayerCount;
    }

    /**
     * 定时将 {@code status:onlineCount:timestamp} 写入 Redis 键 {@code game:status:{gameId}} 并续期 TTL。
     */
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

    /**
     * 关闭时将状态置为 NOT_STARTED 并触发一次同步/事件，避免遗留「可登录」假象。
     */
    @PreDestroy
    public void shutdown() {
        logger.info("GameStatusService shutting down, setting status to NOT_STARTED");
        setStatus(GameStatus.NOT_STARTED);
    }

    /**
     * 从配置 id 解析数值 gameId，失败默认 1001。
     */
    private int parseGameId(String id) {
        try {
            return Integer.parseInt(id.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1001;
        }
    }
}

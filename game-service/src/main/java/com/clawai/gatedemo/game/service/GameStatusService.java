package com.clawai.gatedemo.game.service;

import com.clawai.gatedemo.game.config.GameConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.core.StringRedisTemplate;
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

    private final StringRedisTemplate stringRedisTemplate;
    private final GameConfig gameConfig;
    private final GameRegistryService registryService;

    private GameStatus currentStatus = GameStatus.NOT_STARTED;
    /** 当前在线人数快照，与状态一并写入 Redis（具体由谁更新计数视上层调用）。 */
    private int onlinePlayerCount = 0;
    /** {@link ApplicationReadyEvent} 处理后置 true，此前 setStatus 不写 Redis。 */
    private boolean initialized = false;

    /**
     * @param stringRedisTemplate 状态键纯字符串读写（与 login-service 一致）
     * @param gameConfig       解析 gameId 等
     * @param registryService  状态变化时发布 UPDATE（可为 null 则跳过）
     */
    public GameStatusService(
            StringRedisTemplate stringRedisTemplate,
            GameConfig gameConfig,
            @Lazy GameRegistryService registryService) {
        this.stringRedisTemplate = stringRedisTemplate;
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
     * 应用就绪后补写 Redis（若尚未由 Runner 激活）。
     * <p>
     * 注意：{@link com.clawai.gatedemo.game.GameServiceApplication} 在 {@link org.springframework.boot.CommandLineRunner} 里会
     * {@code latch.await()} 阻塞进程，Spring Boot 仅在<strong>所有</strong> Runner 返回后才发布
     * {@link ApplicationReadyEvent}，因此 Ready 在默认实现下<strong>永远不会先到</strong>。
     * 必须在 Runner 内调用 {@link #activateRedisSyncFromRunner()}，否则 {@code initialized} 一直为 false，
     * {@link #syncStatusToRedis()} 与定时任务都不会写 Redis。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("GameStatusService ApplicationReady, gameId: {}, currentStatus={}", gameConfig.getId(), currentStatus);
        if (!initialized) {
            initialized = true;
            syncStatusToRedis();
        }
    }

    /**
     * 由 {@link com.clawai.gatedemo.game.GameServiceApplication} 在阻塞 {@code latch.await()} 之前调用。
     */
    public void activateRedisSyncFromRunner() {
        logger.info("GameStatusService activating Redis sync from CommandLineRunner (gameId={})", gameConfig.getId());
        initialized = true;
        syncStatusToRedis();
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

            stringRedisTemplate.opsForValue().set(key, value, KEY_EXPIRE_SECONDS, TimeUnit.SECONDS);
            logger.info("Game status synced to Redis: key={}, value={}", key, value);
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

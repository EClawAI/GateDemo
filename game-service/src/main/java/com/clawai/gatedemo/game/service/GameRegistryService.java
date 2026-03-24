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

/**
 * 将本 Game 实例的地址与运行状态写入 Redis 注册表，并通过频道广播注册/注销/状态变更，供 Login 等服务发现与路由。
 */
@Service
public class GameRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(GameRegistryService.class);

    /** Redis 中每条游戏实例注册信息的键前缀，完整键为 {@code game:registry:{gameId}}。 */
    private static final String REGISTRY_KEY_PREFIX = "game:registry:";
    /** 注册/注销/状态变更的 Pub/Sub 频道名。 */
    private static final String EVENT_CHANNEL = "game:events";

    private final RedisTemplate<String, Object> redisTemplate;
    private final GameConfig gameConfig;
    private final GameStatusService gameStatusService;

    /** 应用已就绪且配置开启注册后为 true，避免过早写 Redis。 */
    private boolean initialized = false;

    /**
     * @param redisTemplate     读写注册键与 Pub/Sub
     * @param gameConfig        实例标识、注册开关与 TTL 等
     * @param gameStatusService 读取当前状态写入注册值
     */
    public GameRegistryService(RedisTemplate<String, Object> redisTemplate, 
                               GameConfig gameConfig, 
                               GameStatusService gameStatusService) {
        this.redisTemplate = redisTemplate;
        this.gameConfig = gameConfig;
        this.gameStatusService = gameStatusService;
    }

    /**
     * 应用就绪后若开启注册则置位并执行首次 {@link #register()}。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (!gameConfig.getRegistry().isEnabled()) {
            logger.info("Game registry is disabled");
            return;
        }
        initialized = true;
        register();
    }

    /**
     * 写入本实例 {@code host:port:status:timestamp} 到 Redis 并设置 TTL，随后向 {@link #EVENT_CHANNEL} 发布 REGISTER 事件。
     *
     * @apiNote 未初始化或注册关闭时为 no-op；异常吞掉并打 warn
     */
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

    /**
     * 进程退出前删除注册键并广播 UNREGISTER，便于 Login 等及时摘除路由。
     */
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

    /**
     * 状态变更时向事件频道发送 UPDATE 通知（不含完整注册值，消费者可再查 Redis）。
     *
     * @param oldStatus 变更前枚举数值
     * @param newStatus 变更后枚举数值
     */
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

    /**
     * 从配置 id 字符串中提取数字作为 gameId；无法解析时回退 1001。
     */
    private int parseGameId(String id) {
        try {
            return Integer.parseInt(id.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return 1001;
        }
    }
}

package com.clawai.gatedemo.gate.cluster;

import com.clawai.gatedemo.gate.config.GateConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gate cluster registration manager. Registers this gate instance to Redis on startup,
 * with TTL 60s and refresh every 30s.
 * Key: gate:cluster:{gateId}
 * Value: JSON with host, port, tcpPort, timestamp
 */
@Component
@ConditionalOnProperty(name = "gate.cluster.enabled", havingValue = "true")
public class GateClusterManager {

    private static final Logger logger = LoggerFactory.getLogger(GateClusterManager.class);
    private static final String KEY_PREFIX = "gate:cluster:";
    private static final long TTL_SECONDS = 60;
    private static final long REFRESH_INTERVAL_SECONDS = 30;

    private final GateConfig gateConfig;
    private final RedisTemplate<String, Object> redisTemplate;

    private ScheduledExecutorService scheduler;

    public GateClusterManager(GateConfig gateConfig,
                              RedisTemplate<String, Object> redisTemplate) {
        this.gateConfig = gateConfig;
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-cluster-heartbeat");
            t.setDaemon(true);
            return t;
        });

        // Initial register
        register();
        scheduler.scheduleAtFixedRate(this::register, REFRESH_INTERVAL_SECONDS, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("GateClusterManager started, registering gate {} to Redis every {}s", gateConfig.getId(), REFRESH_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scheduler.shutdownNow();
            }
        }
        remove();
        logger.info("GateClusterManager stopped, removed gate {} from Redis", gateConfig.getId());
    }

    private void register() {
        String key = KEY_PREFIX + gateConfig.getId();
        Map<String, Object> value = new HashMap<>();
        value.put("host", gateConfig.getHost());
        value.put("port", gateConfig.getPort());
        value.put("tcpPort", gateConfig.getTcp().isEnabled() ? gateConfig.getTcp().getPort() : null);
        value.put("timestamp", System.currentTimeMillis());

        try {
            redisTemplate.opsForValue().set(key, value, TTL_SECONDS, TimeUnit.SECONDS);
            logger.debug("Registered gate to Redis: {} -> {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to register gate to Redis: {}", e.getMessage());
        }
    }

    private void remove() {
        String key = KEY_PREFIX + gateConfig.getId();
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            logger.warn("Failed to remove gate key from Redis: {}", e.getMessage());
        }
    }
}

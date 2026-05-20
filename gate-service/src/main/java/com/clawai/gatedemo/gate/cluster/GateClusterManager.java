package com.clawai.gatedemo.gate.cluster;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.transport.GatewayTransport;
import com.clawai.gatedemo.gate.transport.GatewayTransportRegistry;
import com.clawai.gatedemo.gate.transport.TransportInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Gate 集群注册：启动时将本节点信息写入 Redis，TTL 60s、每 30s 续期；其他组件可按 key 发现实例。
 * Key: {@code gate:cluster:{gateId}}，Value: host、port、tcpPort、transports、timestamp。
 *
 * <p>B4：新增 {@code transports} 字段（JSON 数组），列出所有 active {@link GatewayTransport}
 * 的 {@link TransportInfo}；旧字段 {@code port} / {@code tcpPort} 同时保留以维持兼容性。
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
    private final GatewayTransportRegistry transportRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 定时刷新注册信息的单线程调度器（守护线程） */
    private ScheduledExecutorService scheduler;

    /**
     * @param gateConfig        本机 id、监听地址与 TCP 端口等
     * @param redisTemplate     写入集群注册 KV
     * @param transportRegistry transport 注册表，用于产出 {@code transports} 字段
     */
    public GateClusterManager(GateConfig gateConfig,
                              RedisTemplate<String, Object> redisTemplate,
                              GatewayTransportRegistry transportRegistry) {
        this.gateConfig = gateConfig;
        this.redisTemplate = redisTemplate;
        this.transportRegistry = transportRegistry;
    }

    /**
     * 立即注册一次并启动定时节拍；失败仅打日志，不阻止 Bean 就绪。
     */
    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-cluster-heartbeat");
            t.setDaemon(true);
            return t;
        });

        register();
        scheduler.scheduleAtFixedRate(this::register, REFRESH_INTERVAL_SECONDS, REFRESH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        logger.info("GateClusterManager started, registering gate {} to Redis every {}s", gateConfig.getId(), REFRESH_INTERVAL_SECONDS);
    }

    /**
     * 关闭调度器并从 Redis 删除本节点 key；中断时尽力 shutdownNow。
     */
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
        value.put("transports", buildTransportsJson());
        value.put("timestamp", System.currentTimeMillis());

        try {
            redisTemplate.opsForValue().set(key, value, TTL_SECONDS, TimeUnit.SECONDS);
            logger.debug("Registered gate to Redis: {} -> {}", key, value);
        } catch (Exception e) {
            logger.error("Failed to register gate to Redis: {}", e.getMessage());
        }
    }

    /**
     * 把 active transports 序列化为 JSON 数组字符串。
     * <p>使用字符串而不是直接的 {@code List<Map>}，避免 Redis 序列化器对嵌套结构的差异。
     */
    private String buildTransportsJson() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (GatewayTransport t : transportRegistry.active()) {
            TransportInfo i = t.info();
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", i.name());
            entry.put("host", i.host());
            entry.put("port", i.port());
            entry.put("scheme", i.scheme());
            list.add(entry);
        }
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            logger.warn("Failed to serialize transports: {}", e.getMessage());
            return "[]";
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

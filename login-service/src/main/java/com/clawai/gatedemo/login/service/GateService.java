package com.clawai.gatedemo.login.service;

import com.clawai.gatedemo.login.config.LoginConfig;
import com.clawai.gatedemo.login.model.GateHeartbeatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 网关发现与选路服务：周期性从 Redis {@code gate:cluster:*} 扫描已注册的网关实例，
 * 同时接受 HTTP 心跳补充在线人数信息，按「最少在线数」策略选取网关。
 */
@Service
public class GateService {

    private static final Logger logger = LoggerFactory.getLogger(GateService.class);

    /** 与 gate-service GateClusterManager 写入的 key 前缀一致 */
    private static final String GATE_CLUSTER_KEY_PREFIX = "gate:cluster:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final LoginConfig loginConfig;

    /** 内存中的网关最新快照，键为 gateId */
    private final Map<String, GateInstance> gateMap = new ConcurrentHashMap<>();

    public GateService(RedisTemplate<String, Object> redisTemplate, LoginConfig loginConfig) {
        this.redisTemplate = redisTemplate;
        this.loginConfig = loginConfig;
    }

    @PostConstruct
    public void init() {
        refreshGatesFromRedis();
        logger.info("GateService initialized, discovered {} gate(s)", gateMap.size());
    }

    /**
     * 每 10 秒从 Redis 扫描 gate:cluster:* 键，与 GateClusterManager 的 30s 续期 / 60s TTL 配合。
     * key 过期即自然消失，本方法会清除本地已失效的条目。
     */
    @Scheduled(fixedRate = 10000)
    public void refreshGatesFromRedis() {
        try {
            Set<String> keys = redisTemplate.keys(GATE_CLUSTER_KEY_PREFIX + "*");
            if (keys == null || keys.isEmpty()) {
                if (!gateMap.isEmpty()) {
                    logger.warn("No gate:cluster keys found in Redis, clearing local gateMap");
                    gateMap.clear();
                }
                return;
            }

            Set<String> discoveredGateIds = ConcurrentHashMap.newKeySet();

            for (String key : keys) {
                try {
                    Object value = redisTemplate.opsForValue().get(key);
                    if (!(value instanceof Map)) {
                        continue;
                    }

                    @SuppressWarnings("unchecked")
                    Map<String, Object> data = (Map<String, Object>) value;
                    String gateId = key.substring(GATE_CLUSTER_KEY_PREFIX.length());

                    GateInstance instance = gateMap.computeIfAbsent(gateId, k -> new GateInstance());
                    instance.setGateId(gateId);
                    instance.setHost(str(data.get("host")));
                    instance.setPort(toInt(data.get("port")));
                    instance.setLastUpdateTime(toLong(data.get("timestamp")));

                    discoveredGateIds.add(gateId);
                } catch (Exception e) {
                    logger.debug("Failed to parse gate key {}: {}", key, e.getMessage());
                }
            }

            // 清理 Redis 中已过期（key 已不存在）的本地条目
            gateMap.keySet().removeIf(gateId -> {
                if (!discoveredGateIds.contains(gateId)) {
                    logger.info("Gate {} no longer in Redis, removing from local map", gateId);
                    return true;
                }
                return false;
            });

        } catch (Exception e) {
            logger.warn("Failed to refresh gates from Redis: {}", e.getMessage());
        }
    }

    /**
     * 接受网关 HTTP 心跳，补充在线人数到已发现的实例上。
     * 如果该网关尚未被 Redis 发现，也先缓存到内存（兼容旧路径）。
     */
    public void handleHeartbeat(GateHeartbeatRequest request) {
        String gateId = request.getGateId();

        GateInstance instance = gateMap.computeIfAbsent(gateId, k -> {
            GateInstance newInst = new GateInstance();
            newInst.setGateId(gateId);
            newInst.setHost(request.getHost());
            newInst.setPort(request.getPort());
            return newInst;
        });

        instance.setOnline(request.getOnline() != null ? request.getOnline() : 0);
        instance.setLastUpdateTime(System.currentTimeMillis());

        logger.debug("Gate {} heartbeat received, online: {}", gateId, instance.getOnline());
    }

    /**
     * 按「在线数最少优先」选取一台网关，用于登录负载分散。
     *
     * @return 当前有网关时返回最优实例；无数据时 null
     */
    public GateInstance getAvailableGate() {
        if (gateMap.isEmpty()) {
            return null;
        }

        GateInstance minGate = null;
        int minOnline = Integer.MAX_VALUE;

        for (GateInstance gate : gateMap.values()) {
            if (gate.getOnline() < minOnline) {
                minOnline = gate.getOnline();
                minGate = gate;
            }
        }

        return minGate;
    }

    public Map<String, GateInstance> getAllGates() {
        return new ConcurrentHashMap<>(gateMap);
    }

    private static String str(Object o) {
        return o != null ? o.toString() : null;
    }

    private static int toInt(Object o) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(o.toString()); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }

    private static long toLong(Object o) {
        if (o instanceof Number) return ((Number) o).longValue();
        if (o != null) {
            try { return Long.parseLong(o.toString()); } catch (NumberFormatException ignored) {}
        }
        return 0L;
    }

    /** 单台网关的运行时视图 */
    public static class GateInstance {
        private String gateId;
        private String host;
        private Integer port;
        private int online;
        private Long lastUpdateTime;

        public String getGateId() { return gateId; }
        public void setGateId(String gateId) { this.gateId = gateId; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public Integer getPort() { return port; }
        public void setPort(Integer port) { this.port = port; }
        public int getOnline() { return online; }
        public void setOnline(int online) { this.online = online; }
        public Long getLastUpdateTime() { return lastUpdateTime; }
        public void setLastUpdateTime(Long lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
    }
}

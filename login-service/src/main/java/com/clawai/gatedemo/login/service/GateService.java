package com.clawai.gatedemo.login.service;

import com.clawai.gatedemo.login.config.LoginConfig;
import com.clawai.gatedemo.login.model.GateHeartbeatRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 维护网关存活视图：内存缓存结合 Redis TTL 心跳，支撑按在线数选路与查询当前网关列表。
 */
@Service
public class GateService {

    private static final Logger logger = LoggerFactory.getLogger(GateService.class);

    private static final String GATE_KEY_PREFIX = "gate:online:";
    private static final long GATE_EXPIRE_SECONDS = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final LoginConfig loginConfig;

    private final Map<String, GateInstance> gateMap = new ConcurrentHashMap<>();

    public GateService(RedisTemplate<String, Object> redisTemplate, LoginConfig loginConfig) {
        this.redisTemplate = redisTemplate;
        this.loginConfig = loginConfig;
    }

    @PostConstruct
    public void init() {
        logger.info("GateService initialized");
    }

    public void handleHeartbeat(GateHeartbeatRequest request) {
        String gateId = request.getGateId();
        
        GateInstance instance = new GateInstance();
        instance.setGateId(gateId);
        instance.setHost(request.getHost());
        instance.setPort(request.getPort());
        instance.setOnline(request.getOnline() != null ? request.getOnline() : 0);
        instance.setLastUpdateTime(System.currentTimeMillis());
        
        gateMap.put(gateId, instance);
        
        redisTemplate.opsForValue().set(
            GATE_KEY_PREFIX + gateId,
            instance.getOnline(),
            GATE_EXPIRE_SECONDS,
            TimeUnit.SECONDS
        );
        
        logger.debug("Gate {} heartbeat received, online: {}", gateId, instance.getOnline());
    }

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

    public static class GateInstance {
        private String gateId;
        private String host;
        private Integer port;
        private Integer online;
        private Long lastUpdateTime;

        public String getGateId() {
            return gateId;
        }

        public void setGateId(String gateId) {
            this.gateId = gateId;
        }

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public Integer getOnline() {
            return online;
        }

        public void setOnline(Integer online) {
            this.online = online;
        }

        public Long getLastUpdateTime() {
            return lastUpdateTime;
        }

        public void setLastUpdateTime(Long lastUpdateTime) {
            this.lastUpdateTime = lastUpdateTime;
        }
    }
}

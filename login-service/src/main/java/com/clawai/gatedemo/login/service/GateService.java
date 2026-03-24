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

    /** Redis 中网关心跳在线数的键前缀，值带 TTL 用于过期剔除 */
    private static final String GATE_KEY_PREFIX = "gate:online:";
    /** 心跳键过期秒数，超时未上报则视为网关不可用（Redis 侧） */
    private static final long GATE_EXPIRE_SECONDS = 60;

    private final RedisTemplate<String, Object> redisTemplate;
    private final LoginConfig loginConfig;

    /** 内存中的网关最新快照，键为 gateId */
    private final Map<String, GateInstance> gateMap = new ConcurrentHashMap<>();

    /**
     * @param redisTemplate 写入心跳在线数与 TTL
     * @param loginConfig   登录模块配置
     */
    public GateService(RedisTemplate<String, Object> redisTemplate, LoginConfig loginConfig) {
        this.redisTemplate = redisTemplate;
        this.loginConfig = loginConfig;
    }

    /** 服务就绪日志，便于排查启动顺序 */
    @PostConstruct
    public void init() {
        logger.info("GateService initialized");
    }

    /**
     * 合并网关心跳：更新内存实例并在 Redis 写入在线数与过期时间。
     *
     * @param request 网关上报的标识、地址、端口与在线数
     */
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

    /**
     * 按「在线数最少优先」选取一台网关，用于登录负载分散。
     *
     * @return 当前内存中有快照时的最优网关；无数据时 {@code null}
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

    /**
     * 返回网关快照的防御性拷贝，避免外部修改内部 Map。
     *
     * @return gateId 到 {@link GateInstance} 的副本
     */
    public Map<String, GateInstance> getAllGates() {
        return new ConcurrentHashMap<>(gateMap);
    }

    /** 单台网关的运行时视图，由心跳刷新 */
    public static class GateInstance {
        private String gateId;
        private String host;
        private Integer port;
        private Integer online;
        /** 最近一次收到心跳的时间戳（毫秒） */
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

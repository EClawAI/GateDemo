package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Redis 中 FlowSession 元数据的读写封装。
 *
 * <p>Key schema（参见 design.md §5）：
 * <ul>
 *   <li>{@code gate:flow:<flowId>} — Hash，承载 {@link RedisFlowRecord} 全部字段；</li>
 *   <li>{@code gate:flow:byplayer:<playerId>} — String，存当前活跃 flowId；</li>
 * </ul>
 *
 * <p>Lua 脚本 {@code lua/flow_takeover.lua} 提供「单玩家单 flow」原子替换语义。
 *
 * <p>所有写方法对 {@link DataAccessException}（Redis 不可用 / 网络抖动）做 <b>静默降级</b>：
 * 记日志 + 返回失败 sentinel（{@code null} / {@code false}），由上层
 * {@link FlowSessionManager} 决定是否回退到「无 Redis」NEW 路径。
 */
@Service
public class RedisFlowStore {

    private static final Logger logger = LoggerFactory.getLogger(RedisFlowStore.class);

    private static final String FIELD_PLAYER_ID = "playerId";
    private static final String FIELD_GAME_ID = "gameId";
    private static final String FIELD_OWNER_GATE_ID = "ownerGateId";
    private static final String FIELD_CREATED_AT = "createdAt";
    private static final String FIELD_EXPIRES_AT = "expiresAt";
    private static final String FIELD_DETACHED_AT = "detachedAt";
    private static final String FIELD_LAST_SEQ_ANCHOR = "lastSeqAnchor";

    private final StringRedisTemplate redis;
    private final GateConfig.FlowConfig flowConfig;
    private final RedisScript<String> takeoverScript;
    private final RedisScript<String> crossTakeoverScript;

    /**
     * 仅用于测试：跳过 Spring / Redis 装配，子类可重写所有 public 方法。
     * <p>不应在生产代码中调用。
     */
    protected RedisFlowStore(GateConfig.FlowConfig flowConfig) {
        this.redis = null;
        this.flowConfig = flowConfig;
        this.takeoverScript = null;
        this.crossTakeoverScript = null;
    }

    public RedisFlowStore(StringRedisTemplate redis, GateConfig gateConfig) {
        this.redis = redis;
        this.flowConfig = gateConfig.getFlow();
        this.takeoverScript = loadScript("lua/flow_takeover.lua");
        this.crossTakeoverScript = loadScript("lua/flow_cross_takeover.lua");
    }

    private static RedisScript<String> loadScript(String classpathPath) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(new ClassPathResource(classpathPath)));
        script.setResultType(String.class);
        try {
            script.getScriptAsString();
        } catch (Exception e) {
            try {
                String body = new String(new ClassPathResource(classpathPath).getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                script.setScriptText(body);
            } catch (IOException ioe) {
                throw new IllegalStateException("Failed to load " + classpathPath, ioe);
            }
        }
        return script;
    }

    /** {@code gate:flow:<flowId>} 完整 key. */
    public String flowKey(String flowId) {
        return flowConfig.getRedisKeyPrefix() + flowId;
    }

    /** {@code gate:flow:byplayer:<playerId>} 完整 key. */
    public String byPlayerKey(long playerId) {
        return flowConfig.getRedisKeyPrefix() + "byplayer:" + playerId;
    }

    /**
     * 通过 Lua 脚本原子地：踢掉该 playerId 旧 flow（若存在），写入新 flow hash + byplayer 索引。
     *
     * @return 包含 ok 标识（Redis 调用成功）与被踢旧 flowId（无则 {@code null}）的结果；
     *         Redis 不可用时返回 {@link TakeoverResult#unavailable()}，由调用方决定降级策略
     */
    public TakeoverResult takeover(RedisFlowRecord newRecord) {
        long ttlMs = Math.max(1L, newRecord.expiresAt() - System.currentTimeMillis());
        List<String> keys = Arrays.asList(flowKey(newRecord.flowId()), byPlayerKey(newRecord.playerId()));
        Object[] args = new Object[] {
                newRecord.flowId(),
                String.valueOf(newRecord.playerId()),
                String.valueOf(newRecord.gameId()),
                newRecord.ownerGateId(),
                String.valueOf(newRecord.createdAt()),
                String.valueOf(newRecord.expiresAt()),
                String.valueOf(newRecord.lastSeqAnchor()),
                String.valueOf(ttlMs)
        };
        try {
            String evicted = redis.execute(takeoverScript, keys, args);
            return new TakeoverResult(true, evicted);
        } catch (DataAccessException e) {
            logger.warn("Redis takeover failed for flowId={} playerId={}: {}",
                    newRecord.flowId(), newRecord.playerId(), e.getMessage());
            return TakeoverResult.unavailable();
        }
    }

    /**
     * {@link #takeover} 返回值：{@code ok=false} 表示 Redis 调用失败，需要走纯内存降级；
     * {@code evictedFlowId} 为被原子顶替的旧 flowId，无旧 flow 时为 {@code null}。
     */
    public record TakeoverResult(boolean ok, String evictedFlowId) {
        public static TakeoverResult unavailable() { return new TakeoverResult(false, null); }
    }

    /**
     * B2：原子地把 flowId 的 owner 改写为本实例，返回旧 owner 字符串以驱动 Pub/Sub eviction。
     *
     * @param flowId           目标 flowId
     * @param playerId         flow 对应 playerId（用于 byPlayer key PEXPIRE）
     * @param newOwnerGateId   本实例的 gate.id
     * @param newExpiresAtMs   本次绑定后的新 expiresAt（epoch ms）
     * @return {@link CrossTakeoverResult}，含 state + previousOwnerGateId
     */
    public CrossTakeoverResult crossTakeover(String flowId, long playerId,
                                             String newOwnerGateId, long newExpiresAtMs) {
        long ttlMs = Math.max(1L, newExpiresAtMs - System.currentTimeMillis());
        List<String> keys = Arrays.asList(flowKey(flowId), byPlayerKey(playerId));
        Object[] args = new Object[] {
                newOwnerGateId,
                String.valueOf(newExpiresAtMs),
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis())
        };
        try {
            String raw = redis.execute(crossTakeoverScript, keys, args);
            if (raw == null) return CrossTakeoverResult.expired();
            if ("EXPIRED".equals(raw)) return CrossTakeoverResult.expired();
            if ("SAME".equals(raw)) return new CrossTakeoverResult(true, newOwnerGateId, CrossState.RESUMED_OWNER_SAME);
            return new CrossTakeoverResult(true, raw, CrossState.RESUMED_OWNER_CHANGED);
        } catch (DataAccessException e) {
            logger.warn("Redis crossTakeover failed flowId={} newOwner={}: {}",
                    flowId, newOwnerGateId, e.getMessage());
            return CrossTakeoverResult.unavailable();
        }
    }

    /** B2：跨实例迁移结果分类。 */
    public enum CrossState { RESUMED_OWNER_SAME, RESUMED_OWNER_CHANGED, EXPIRED, REDIS_UNAVAILABLE }

    /** B2：{@link #crossTakeover} 返回结构。 */
    public record CrossTakeoverResult(boolean ok, String previousOwnerGateId, CrossState state) {
        public static CrossTakeoverResult expired() { return new CrossTakeoverResult(true, null, CrossState.EXPIRED); }
        public static CrossTakeoverResult unavailable() { return new CrossTakeoverResult(false, null, CrossState.REDIS_UNAVAILABLE); }
    }

    /** 读取 flow hash；不存在 / Redis 故障返回 {@code null}. */
    public RedisFlowRecord loadByFlowId(String flowId) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(flowKey(flowId));
            if (raw == null || raw.isEmpty()) {
                return null;
            }
            return new RedisFlowRecord(
                    flowId,
                    parseLong(raw.get(FIELD_PLAYER_ID), 0L),
                    (int) parseLong(raw.get(FIELD_GAME_ID), 0L),
                    String.valueOf(raw.getOrDefault(FIELD_OWNER_GATE_ID, "")),
                    parseLong(raw.get(FIELD_CREATED_AT), 0L),
                    parseLong(raw.get(FIELD_EXPIRES_AT), 0L),
                    parseLong(raw.get(FIELD_DETACHED_AT), 0L),
                    parseLong(raw.get(FIELD_LAST_SEQ_ANCHOR), 0L));
        } catch (DataAccessException e) {
            logger.warn("Redis loadByFlowId failed flowId={}: {}", flowId, e.getMessage());
            return null;
        }
    }

    /** 通过 byplayer 索引解析 flowId；不存在 / Redis 故障返回 {@code null}. */
    public String loadFlowIdByPlayer(long playerId) {
        try {
            return redis.opsForValue().get(byPlayerKey(playerId));
        } catch (DataAccessException e) {
            logger.warn("Redis loadFlowIdByPlayer failed playerId={}: {}", playerId, e.getMessage());
            return null;
        }
    }

    /**
     * 标记 flow 进入 DETACHED：更新 {@code detachedAt} 字段；不重设 TTL（依赖原 TTL 兜底回收）。
     */
    public boolean markDetached(String flowId, long detachedAt) {
        try {
            String key = flowKey(flowId);
            // Use HSET (RedisTemplate exposes via opsForHash().put) to keep behaviour atomic-ish per field.
            redis.opsForHash().put(key, FIELD_DETACHED_AT, String.valueOf(detachedAt));
            return true;
        } catch (DataAccessException e) {
            logger.warn("Redis markDetached failed flowId={}: {}", flowId, e.getMessage());
            return false;
        }
    }

    /**
     * B1：更新 flow hash 的 {@code lastSeqAnchor} 字段，反映客户端最近 ACK 的 gwSeq；
     * 仅 HSET 一个字段，不重设 TTL。Redis 失败静默降级。
     */
    public boolean markAckedSeq(String flowId, long ackedSeq) {
        try {
            String key = flowKey(flowId);
            redis.opsForHash().put(key, FIELD_LAST_SEQ_ANCHOR, String.valueOf(ackedSeq));
            return true;
        } catch (DataAccessException e) {
            logger.warn("Redis markAckedSeq failed flowId={}: {}", flowId, e.getMessage());
            return false;
        }
    }

    /**
     * 续期：在 ATTACHED 状态下将 flow hash + byplayer 索引的 TTL 延长到指定 expiresAt。
     */
    public boolean renew(String flowId, long playerId, long expiresAt) {
        long ttlMs = Math.max(1L, expiresAt - System.currentTimeMillis());
        try {
            String key = flowKey(flowId);
            redis.opsForHash().put(key, FIELD_EXPIRES_AT, String.valueOf(expiresAt));
            redis.expire(key, java.time.Duration.ofMillis(ttlMs));
            redis.expire(byPlayerKey(playerId), java.time.Duration.ofMillis(ttlMs));
            return true;
        } catch (DataAccessException e) {
            logger.warn("Redis renew failed flowId={}: {}", flowId, e.getMessage());
            return false;
        }
    }

    /**
     * 销毁：删除 flow hash 与 byplayer 索引（owner guard：仅当 byplayer 当前仍指向该 flowId 才 DEL byplayer，
     * 防止刚被新 flow 顶号的索引被误删）。
     */
    public boolean destroy(String flowId, long playerId) {
        try {
            String currentByPlayer = redis.opsForValue().get(byPlayerKey(playerId));
            redis.delete(flowKey(flowId));
            if (flowId.equals(currentByPlayer)) {
                redis.delete(byPlayerKey(playerId));
            }
            return true;
        } catch (DataAccessException e) {
            logger.warn("Redis destroy failed flowId={} playerId={}: {}", flowId, playerId, e.getMessage());
            return false;
        }
    }

    /**
     * 健康探测：用于 metrics / 启动诊断。失败返回 false（不抛）。
     */
    public boolean ping() {
        try {
            return "PONG".equalsIgnoreCase(
                    redis.getConnectionFactory() == null
                            ? null
                            : redis.getConnectionFactory().getConnection().ping());
        } catch (Exception e) {
            return false;
        }
    }

    private static long parseLong(Object raw, long fallback) {
        if (raw == null) return fallback;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 仅用于测试 / 诊断。 */
    public GateConfig.FlowConfig getFlowConfig() {
        return flowConfig;
    }

    /** 仅用于测试 / 诊断。 */
    public List<String> debugDescribe(String flowId, long playerId) {
        List<String> out = new ArrayList<>(2);
        out.add(flowKey(flowId));
        out.add(byPlayerKey(playerId));
        return out;
    }
}

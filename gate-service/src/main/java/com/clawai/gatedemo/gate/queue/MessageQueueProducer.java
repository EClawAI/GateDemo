package com.clawai.gatedemo.gate.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

/**
 * 消息队列生产者 - 将消息发送到Redis Stream
 *
 * 设计原理：
 * Redis Stream是Redis 5.0引入的数据结构，类似于Kafka的消息队列。
 * 支持消息持久化、消费者组、消息ID等特性。
 *
 * 核心功能：
 * 1. sendToGame: 发送消息给游戏服（实时消息）
 * 2. saveOfflineMessage: 存储离线消息
 * 3. getOfflineMessageCount: 获取离线消息数量
 * 4. deleteOfflineMessages: 删除离线消息
 *
 * Redis Stream vs Redis Pub/Sub：
 * | 特性 | Stream | Pub/Sub |
 * |------|--------|---------|
 * | 消息持久化 | ✓ | ✗ |
 * | 消费者组 | ✓ | ✗ |
 * | 消息确认 | ✓ | ✗ |
 * | 消息回溯 | ✓ | ✗ |
 *
 * 为什么选择Stream而不是Pub/Sub？
 * 1. 消息持久化：网关重启时消息不丢失
 * 2. 消费者组：支持多Gate实例负载均衡
 * 3. 消息确认：确保消息被正确处理
 *
 * Stream Key设计：
 * - game:message:queue: Gate→Game的消息队列
 * - game:offline:messages:{playerId}: 玩家离线消息
 */
@Component
public class MessageQueueProducer {

    private static final Logger logger = LoggerFactory.getLogger(MessageQueueProducer.class);

    /** Gate→Game 消息队列的Stream Key */
    private static final String STREAM_KEY = "game:message:queue";

    /** 离线消息的Stream Key前缀（按 playerId 兜底） */
    private static final String OFFLINE_STREAM_KEY = "game:offline:messages";

    /** B3：flowId 隔离的离线消息 Stream Key 前缀（推荐路径） */
    private static final String OFFLINE_FLOW_STREAM_KEY = "game:offline:flow";

    /** B3 字段名 */
    private static final String FIELD_FLOW_ID = "flow_id";
    private static final String FIELD_PLAYER_ID = "player_id";
    private static final String FIELD_GW_SEQ = "gw_seq";
    private static final String FIELD_MSG_ID = "msg_id";
    private static final String FIELD_FLAGS = "flags";
    private static final String FIELD_BODY_B64 = "body_b64";
    private static final String FIELD_TS = "ts";

    /** Redis操作模板 */
    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 构造函数
     * @param redisTemplate Redis操作模板，Spring自动注入
     */
    public MessageQueueProducer(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 发送消息给游戏服务
     *
     * 使用场景：
     * - 玩家发送聊天消息 → 转发给Game
     * - 玩家执行游戏操作 → 转发给Game
     *
     * 消息流程：
     * Gate → Redis Stream(game:message:queue) → Game
     *
     * @param playerId 玩家ID
     * @param messageId 消息ID
     * @param messageData 消息内容
     * @return 消息RecordId，发送失败返回null
     */
    public String sendToGame(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            // 构建消息内容
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

            // 使用Spring Data Redis的Stream API发送消息
            // Redis Stream会自动生成消息ID（格式：时间戳-序号）
            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(STREAM_KEY)
                    .ofMap(message));

            logger.debug("Message sent to game queue: playerId={}, msgId={}, recordId={}",
                    playerId, messageId, recordId);
            
            // 返回消息ID，便于追踪
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.error("Failed to send message to game queue: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 保存离线消息
     *
     * 使用场景：
     * - 玩家离线时，有其他玩家发送消息
     * - 玩家上线时拉取离线消息
     *
     * 消息存储结构：
     * Key: game:offline:messages:{playerId}
     * Value: {player_id, message_id, data, timestamp}
     *
     * @param playerId 目标玩家ID
     * @param messageId 消息ID
     * @param messageData 消息内容
     * @return 消息RecordId
     * @deprecated B3：playerId 兜底 stream 写入已由 {@link com.clawai.gatedemo.gate.service.OfflineMessageService#storeForOffline}
     *             封装，并辅以 gwSeq 标识；本方法保留仅作旧调用方兼容，新代码请改用
     *             {@code OfflineMessageService.storeForOffline} 或 {@link #saveOfflineFrame}。
     *             待 game-service 完全切换后再行删除（不要自动删除）。
     */
    @Deprecated(forRemoval = false)
    public String saveOfflineMessage(Long playerId, short messageId, Map<String, Object> messageData) {
        try {
            Map<String, Object> message = new HashMap<>();
            message.put("player_id", playerId);
            message.put("message_id", messageId);
            message.put("timestamp", System.currentTimeMillis());
            message.put("data", messageData);

            // 每个玩家一个独立的Stream
            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(getOfflineStreamKey(playerId))
                    .ofMap(message));

            logger.debug("Offline message saved: playerId={}, msgId={}, recordId={}",
                    playerId, messageId, recordId);
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.error("Failed to save offline message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 生成离线消息的Stream Key
     *
     * 设计：每个玩家一个Stream，便于隔离和管理
     * Key格式：game:offline:messages:12345
     *
     * @param playerId 玩家ID
     * @return Stream Key
     */
    private String getOfflineStreamKey(Long playerId) {
        return OFFLINE_STREAM_KEY + ":" + playerId;
    }

    /**
     * 获取离线消息数量
     *
     * 用于判断是否需要触发relogin
     *
     * @param playerId 玩家ID
     * @return 离线消息数量
     */
    public long getOfflineMessageCount(Long playerId) {
        try {
            return redisTemplate.opsForStream().size(getOfflineStreamKey(playerId));
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 删除离线消息
     *
     * 使用场景：
     * 1. 玩家触发relogin时
     * 2. 玩家上线拉取完消息后
     *
     * @param playerId 玩家ID
     */
    public void deleteOfflineMessages(Long playerId) {
        try {
            String key = getOfflineStreamKey(playerId);
            redisTemplate.delete(key);
            logger.info("Deleted offline messages for player: {}", playerId);
        } catch (Exception e) {
            logger.error("Failed to delete offline messages: {}", e.getMessage());
        }
    }

    // ==================== B3：flowId 隔离的离线消息 API ====================

    /** 生成 flowId 隔离 stream 的 key：{@code game:offline:flow:<flowId>}。 */
    public String getOfflineFlowKey(String flowId) {
        return OFFLINE_FLOW_STREAM_KEY + ":" + flowId;
    }

    /**
     * B3：DETACHED 状态下持久化下行帧到 flowId 隔离 stream。字段含 gwSeq + body base64。
     *
     * @return RecordId（成功）；Redis 故障返回 {@code null}
     */
    public String saveOfflineFrame(String flowId, long playerId, long gwSeq,
                                   short flags, int messageId, byte[] body,
                                   Duration ttl) {
        try {
            Map<String, String> message = new LinkedHashMap<>();
            message.put(FIELD_FLOW_ID, flowId);
            message.put(FIELD_PLAYER_ID, String.valueOf(playerId));
            message.put(FIELD_GW_SEQ, String.valueOf(gwSeq));
            message.put(FIELD_FLAGS, String.valueOf(flags & 0xFFFF));
            message.put(FIELD_MSG_ID, String.valueOf(messageId));
            message.put(FIELD_BODY_B64, Base64.getEncoder().encodeToString(body != null ? body : new byte[0]));
            message.put(FIELD_TS, String.valueOf(System.currentTimeMillis()));

            String key = getOfflineFlowKey(flowId);
            RecordId recordId = redisTemplate.opsForStream().add(StreamRecords.newRecord()
                    .in(key)
                    .ofMap((Map) message));
            if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
                redisTemplate.expire(key, ttl);
            }
            return recordId != null ? recordId.getValue() : null;
        } catch (Exception e) {
            logger.warn("saveOfflineFrame failed flowId={} gwSeq={}: {}", flowId, gwSeq, e.getMessage());
            return null;
        }
    }

    /**
     * B3：读取 flowId 隔离 stream 中 {@code gwSeq > afterGwSeq} 的条目。返回按 stream 自身顺序，
     * 由上层按 {@code gwSeq} 排序后投递。
     */
    public List<OfflineEntry> readOfflineFrames(String flowId, long afterGwSeq, int batchSize) {
        try {
            String key = getOfflineFlowKey(flowId);
            // MVP：单 flow stream 上限受 capacity 控制，简单 XRANGE 全读后内存过滤；
            // batchSize 仅作上层截断信号，后续可改 XLEN + 分批拉。
            List<MapRecord<String, Object, Object>> raw =
                    redisTemplate.opsForStream().range(key, Range.unbounded());
            if (raw == null || raw.isEmpty()) return Collections.emptyList();
            int limit = batchSize <= 0 ? 100 : batchSize;

            List<OfflineEntry> out = new ArrayList<>(Math.min(raw.size(), limit));
            for (MapRecord<String, Object, Object> rec : raw) {
                if (out.size() >= limit) break;
                Map<Object, Object> v = rec.getValue();
                long gwSeq = parseLong(v.get(FIELD_GW_SEQ), 0L);
                if (gwSeq <= afterGwSeq) continue;
                short flags = (short) parseLong(v.get(FIELD_FLAGS), 0L);
                int msgId = (int) parseLong(v.get(FIELD_MSG_ID), 0L);
                long playerId = parseLong(v.get(FIELD_PLAYER_ID), 0L);
                String b64 = String.valueOf(v.getOrDefault(FIELD_BODY_B64, ""));
                byte[] body = b64.isEmpty() ? new byte[0] : Base64.getDecoder().decode(b64);
                out.add(new OfflineEntry(rec.getId().getValue(), playerId, gwSeq, flags, msgId, body));
            }
            return out;
        } catch (Exception e) {
            logger.warn("readOfflineFrames failed flowId={}: {}", flowId, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** B3：批量 XDEL flowId 隔离 stream 中的 record。返回实际删除条数。 */
    public long deleteOfflineEntries(String flowId, List<String> recordIds) {
        if (recordIds == null || recordIds.isEmpty()) return 0L;
        try {
            String key = getOfflineFlowKey(flowId);
            RecordId[] ids = recordIds.stream().map(RecordId::of).toArray(RecordId[]::new);
            Long n = redisTemplate.opsForStream().delete(key, ids);
            return n == null ? 0L : n;
        } catch (Exception e) {
            logger.warn("deleteOfflineEntries failed flowId={} count={}: {}",
                    flowId, recordIds.size(), e.getMessage());
            return 0L;
        }
    }

    /** B3：删除 flowId 隔离 stream 整 key（destroy / 清理时使用）。 */
    public void deleteOfflineFlow(String flowId) {
        try {
            redisTemplate.delete(getOfflineFlowKey(flowId));
        } catch (Exception e) {
            logger.warn("deleteOfflineFlow failed flowId={}: {}", flowId, e.getMessage());
        }
    }

    /** B3：查询 flow stream 中最大 gw_seq；O(stream.size)，cross-instance nextGwSeq 恢复用。 */
    public long maxOfflineGwSeq(String flowId) {
        try {
            String key = getOfflineFlowKey(flowId);
            List<MapRecord<String, Object, Object>> raw = redisTemplate.opsForStream()
                    .range(key, Range.unbounded());
            if (raw == null || raw.isEmpty()) return 0L;
            long max = 0L;
            for (MapRecord<String, Object, Object> rec : raw) {
                long s = parseLong(rec.getValue().get(FIELD_GW_SEQ), 0L);
                if (s > max) max = s;
            }
            return max;
        } catch (Exception e) {
            logger.warn("maxOfflineGwSeq failed flowId={}: {}", flowId, e.getMessage());
            return 0L;
        }
    }

    /** B3：查询 flow stream 长度。 */
    public long offlineFlowSize(String flowId) {
        try {
            Long n = redisTemplate.opsForStream().size(getOfflineFlowKey(flowId));
            return n == null ? 0L : n;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseLong(Object raw, long fallback) {
        if (raw == null) return fallback;
        try { return Long.parseLong(raw.toString()); } catch (NumberFormatException e) { return fallback; }
    }

    /** B3：一条 offline 帧解码后的结构。 */
    public record OfflineEntry(String recordId, long playerId, long gwSeq,
                               short flags, int messageId, byte[] body) {}
}

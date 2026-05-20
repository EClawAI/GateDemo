package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.DownstreamBuffer;
import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import com.clawai.gatedemo.proto.gate.ErrorResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 离线消息服务：B3 引入「DETACHED 期间持久化 + RESUME 合流」语义。
 *
 * <p>三类入口：
 * <ul>
 *   <li>{@link #storeForDetached(FlowSession, WrappedMessage)} — DETACHED 状态下推送，
 *       stamp gwSeq 后写入 flowId 隔离 stream；</li>
 *   <li>{@link #storeForOffline(long, WrappedMessage)} — 完全无 session 时写入 playerId 兜底 stream；</li>
 *   <li>{@link #flushBufferToOffline(FlowSession)} — destroy/evict 前将未 ACK buffer 帧 flush 到 stream。</li>
 * </ul>
 *
 * <p>合流：{@link #replayAndMerge(FlowSession, Channel, long)} 由
 * {@link com.clawai.gatedemo.gate.flow.FlowSessionManager#resume} 在 buffer 重放后调用。
 *
 * <p>所有方法对 Redis 故障静默降级（log warn + metric），不抛出。
 */
@Service
public class OfflineMessageService {

    private static final Logger logger = LoggerFactory.getLogger(OfflineMessageService.class);
    private static final Logger flowEventLogger = LoggerFactory.getLogger("gate.flow.event");

    private static final int MSG_ID_RELOGIN = MessageRouteRegistry.getIdByName("ErrorResponse");

    private final MessageQueueProducer producer;
    private final PlayerService playerService;
    private final GateConfig gateConfig;
    private final MeterRegistry meterRegistry;
    /**
     * 新观测性入口（{@code add-flow-observability-buckets}）：
     * 通过 {@link FlowSessionManager#recordReplay} 转写 {@code gate_flow_replay_total{source=offline,outcome=*}}。
     * lazy 注入避免与 manager 形成构造期循环依赖；测试环境可为 null（recordReplay 自然跳过）。
     */
    private FlowSessionManager flowSessionManager;

    public OfflineMessageService(MessageQueueProducer producer,
                                 @Lazy PlayerService playerService,
                                 GateConfig gateConfig,
                                 @Autowired(required = false) MeterRegistry meterRegistry) {
        this.producer = producer;
        this.playerService = playerService;
        this.gateConfig = gateConfig;
        this.meterRegistry = meterRegistry;
    }

    /**
     * Lazy 注入 manager，避免构造期循环依赖（{@code FlowSessionManager} 也通过 setter 注入本类）。
     * 单测环境不调用此方法时 {@code recordReplay} 自然跳过。
     */
    @Autowired(required = false)
    public void setFlowSessionManager(@Lazy FlowSessionManager flowSessionManager) {
        this.flowSessionManager = flowSessionManager;
    }

    /**
     * 启动时打印一次 offline 配置摘要，便于在多实例 / 多环境部署时迅速核对生效值。
     */
    @jakarta.annotation.PostConstruct
    public void logConfigSummary() {
        GateConfig.OfflineConfig cfg = offlineCfg();
        if (cfg == null) {
            logger.info("event=offline.config.summary enabled=false (no config)");
            return;
        }
        logger.info(
                "event=offline.config.summary enabled={} retentionDays={} reloginThreshold={} replayBatchSize={} flushOnDestroy={} flushOnCrossEvict={}",
                cfg.isEnabled(), cfg.getRetentionDays(), cfg.getReloginThreshold(),
                cfg.getReplayBatchSize(), cfg.isFlushOnDestroy(), cfg.isFlushOnCrossEvict());
    }

    // ==================== Phase A：在线 / 离线钩子 ====================

    public void onPlayerOffline(Long playerId) {
        logger.info("Player {} went offline", playerId);
    }

    /**
     * NEW 首登钩子：drain 兜底 stream + 超阈值 RELOGIN。RESUMED 路径由 manager 内部
     * {@link #replayAndMerge} 处理，不应再调用本方法。
     */
    public void onPlayerOnline(Long playerId, Channel channel) {
        if (!isEnabled()) return;
        long messageCount = producer.getOfflineMessageCount(playerId);
        logger.info("Player {} online, offline fallback messages: {}", playerId, messageCount);

        int threshold = offlineCfg().getReloginThreshold();
        if (messageCount > threshold) {
            handleReloginMode(playerId, threshold, messageCount);
        } else {
            handleNormalMode(playerId, channel);
        }
    }

    private void handleReloginMode(Long playerId, int threshold, long actual) {
        logger.warn("Player {} offline messages {} exceed threshold {}, triggering relogin",
                playerId, actual, threshold);
        producer.deleteOfflineMessages(playerId);
        incrCounter("gate_flow_offline_dropped_total", Tags.of(Tag.of("reason", "relogin_threshold")), actual);
        sendReloginNotification(playerId, threshold);
    }

    private void sendReloginNotification(Long playerId, int threshold) {
        ErrorResponse body = ErrorResponse.newBuilder()
                .setCode("RELOGIN_REQUIRED")
                .setMessage("offline_messages_exceeded, threshold=" + threshold)
                .build();

        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(MSG_ID_RELOGIN);
        msg.getHeader().setMode(MessageHeader.MODE_PUSH);
        msg.setBody(new RawMessageBody(body.toByteArray()));

        boolean sent = playerService.sendToPlayer(playerId, msg);
        if (sent) {
            logger.info("Sent relogin notification to player {}", playerId);
        } else {
            logger.warn("Failed to send relogin notification to player {}", playerId);
        }
    }

    /**
     * B3：NEW 首登的兜底 stream drain。目前仅记录长度日志；后续可在 channel 上重放（无 gwSeq 老条目按顺序写）。
     * MVP 阶段保持现状以避免与 game-service 重复推送同一消息。
     */
    private void handleNormalMode(Long playerId, Channel channel) {
        logger.debug("Offline normal-mode drain skipped for player {} (count below threshold)", playerId);
    }

    // ==================== B3：FlowSession 驱动的离线 / 合流入口 ====================

    /**
     * B3：玩家 DETACHED 时由 {@link PlayerService#sendToPlayer} 调用。
     * stamp gwSeq + 写入 {@code game:offline:flow:<flowId>} stream。
     *
     * @return 是否成功持久化；Redis 故障 / 未启用 offline → false
     */
    public boolean storeForDetached(FlowSession session, WrappedMessage message) {
        if (!isEnabled()) return false;
        if (session == null || message == null) return false;
        long gwSeq = session.incrementAndGetGwSeq();
        MessageHeader header = message.getHeader();
        if (header == null) {
            header = new MessageHeader();
            message.setHeader(header);
        }
        header.setHasGwSeq(true);
        header.setGwSeq(gwSeq);
        byte[] body = message.getBody() != null ? message.getBody().toBytes() : new byte[0];

        String recordId = producer.saveOfflineFrame(
                session.getFlowId(), session.getPlayerId(), gwSeq,
                header.getFlags(), header.getMessageId(), body,
                retentionTtl());

        if (recordId == null) {
            incrCounter("gate_flow_offline_dropped_total", Tags.of(Tag.of("reason", "redis_unavailable")), 1);
            return false;
        }
        incrCounter("gate_flow_offline_stored_total", Tags.of(Tag.of("reason", "detached")), 1);
        flowEventLogger.info("event=flow.offline.stored flowId={} playerId={} gwSeq={} reason=detached recordId={}",
                session.getFlowId(), session.getPlayerId(), gwSeq, recordId);
        return true;
    }

    /**
     * B3：完全没有 FlowSession 时（destroy 后、跨实例 evict 后、首登前）使用，仅写入
     * playerId 兜底 stream（不 stamp gwSeq）。NEW 首登时由 {@link #onPlayerOnline} drain。
     */
    public boolean storeForOffline(long playerId, WrappedMessage message) {
        if (!isEnabled()) return false;
        if (message == null) return false;
        int msgId = message.getHeader() != null ? message.getHeader().getMessageId() : 0;
        byte[] body = message.getBody() != null ? message.getBody().toBytes() : new byte[0];

        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("body_b64", java.util.Base64.getEncoder().encodeToString(body));
        String recordId = producer.saveOfflineMessage(playerId, (short) msgId, data);
        if (recordId == null) {
            incrCounter("gate_flow_offline_dropped_total", Tags.of(Tag.of("reason", "redis_unavailable")), 1);
            return false;
        }
        incrCounter("gate_flow_offline_stored_total", Tags.of(Tag.of("reason", "no_session")), 1);
        flowEventLogger.info("event=flow.offline.stored flowId=- playerId={} gwSeq=0 reason=no_session recordId={}",
                playerId, recordId);
        return true;
    }

    /**
     * B3：destroy / cross-evict 前把 buffer 中 {@code gwSeq > lastSeqAnchor} 的帧 flush 到 offline stream。
     *
     * @return flushed 帧数（0 = buffer 空 / 未启用 / Redis 不可用）
     */
    public int flushBufferToOffline(FlowSession session, String reason) {
        if (!isEnabled() || session == null) return 0;
        DownstreamBuffer buf = session.getBuffer();
        if (buf == null || buf.size() == 0) return 0;
        long fromSeq = session.getLastSeqAnchor();
        List<DownstreamBuffer.BufferedFrame> pending = buf.drain(fromSeq);
        if (pending.isEmpty()) return 0;

        int flushed = 0;
        Duration ttl = retentionTtl();
        for (DownstreamBuffer.BufferedFrame f : pending) {
            WrappedMessage frame = f.frame();
            MessageHeader h = frame.getHeader();
            byte[] body = frame.getBody() != null ? frame.getBody().toBytes() : new byte[0];
            String rid = producer.saveOfflineFrame(
                    session.getFlowId(), session.getPlayerId(), f.gwSeq(),
                    h != null ? h.getFlags() : (short) 0,
                    h != null ? h.getMessageId() : 0,
                    body, ttl);
            if (rid != null) flushed++;
        }
        if (flushed > 0) {
            incrCounter("gate_flow_offline_stored_total", Tags.of(Tag.of("reason", "flush")), flushed);
            incrCounter("gate_flow_offline_flush_total", Tags.of(Tag.of("reason", reason)), 1);
            flowEventLogger.info("event=flow.offline.flushed flowId={} playerId={} count={} reason={}",
                    session.getFlowId(), session.getPlayerId(), flushed, reason);
        }
        return flushed;
    }

    /**
     * B3：RESUME 合流。读取 {@code game:offline:flow:<flowId>} 中 {@code gwSeq > fromSeq} 的条目，
     * 按 gwSeq 升序写入新 channel；成功投递后 XDEL。
     *
     * @return 实际投递条数
     */
    public ReplayResult replayAndMerge(FlowSession session, Channel channel, long fromSeq) {
        if (!isEnabled() || session == null || channel == null || !channel.isActive()) {
            return ReplayResult.empty(fromSeq);
        }
        int batch = offlineCfg().getReplayBatchSize();
        List<MessageQueueProducer.OfflineEntry> entries =
                producer.readOfflineFrames(session.getFlowId(), fromSeq, batch);
        if (entries.isEmpty()) return ReplayResult.empty(fromSeq);

        // 防御性：按 gwSeq 升序排序 + 过滤重复 / 已 ACK
        List<MessageQueueProducer.OfflineEntry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingLong(MessageQueueProducer.OfflineEntry::gwSeq));

        List<String> deliveredIds = new ArrayList<>(sorted.size());
        long maxSeq = fromSeq;
        long dupDropped = 0;
        for (MessageQueueProducer.OfflineEntry e : sorted) {
            if (e.gwSeq() <= fromSeq) {
                dupDropped++;
                continue;
            }
            WrappedMessage replay = new WrappedMessage();
            MessageHeader h = new MessageHeader(e.messageId());
            h.setFlags(e.flags());
            h.setHasGwSeq(true);
            h.setGwSeq(e.gwSeq());
            h.setMode(MessageHeader.MODE_PUSH);
            replay.setHeader(h);
            replay.setBody(new RawMessageBody(e.body()));
            channel.write(replay);
            deliveredIds.add(e.recordId());
            if (e.gwSeq() > maxSeq) maxSeq = e.gwSeq();
        }
        channel.flush();
        if (dupDropped > 0) {
            incrCounter("gate_flow_offline_dropped_total", Tags.of(Tag.of("reason", "dup_or_acked")), dupDropped);
            // 新观测性：source=offline, outcome=skipped_acked
            recordReplay(FlowMetrics.SOURCE_OFFLINE, FlowMetrics.REPLAY_SKIPPED_ACKED, (int) dupDropped);
        }

        long deleted = producer.deleteOfflineEntries(session.getFlowId(), deliveredIds);
        long replayed = deliveredIds.size();
        if (replayed > 0) {
            incrCounter("gate_flow_offline_replayed_total", Tags.empty(), replayed);
            // 新观测性：source=offline, outcome=replayed
            recordReplay(FlowMetrics.SOURCE_OFFLINE, FlowMetrics.REPLAY_REPLAYED, (int) replayed);
            flowEventLogger.info("event=flow.offline.replayed flowId={} playerId={} count={} fromSeq={} toSeq={} xdel={}",
                    session.getFlowId(), session.getPlayerId(), replayed, fromSeq, maxSeq, deleted);
        }
        return new ReplayResult((int) replayed, fromSeq, maxSeq);
    }

    /** lazy 转发到 {@link FlowSessionManager#recordReplay}；manager 未注入时静默跳过。 */
    private void recordReplay(String source, String outcome, int count) {
        if (flowSessionManager != null && count > 0) {
            flowSessionManager.recordReplay(source, outcome, count);
        }
    }

    /** B3：跨实例 RESUME 时查 offline 最大 gwSeq，用于初始化 {@link FlowSession#getNextGwSeq()}. */
    public long maxGwSeq(String flowId) {
        if (!isEnabled() || flowId == null) return 0L;
        return producer.maxOfflineGwSeq(flowId);
    }

    /** B3：flow 彻底销毁后清掉对应 stream（reason 决定是否调用）。 */
    public void deleteFlowStream(String flowId) {
        if (!isEnabled() || flowId == null) return;
        producer.deleteOfflineFlow(flowId);
    }

    /**
     * B3：查询某 flow 在 Redis offline stream 中的当前长度，供 /debug/flows 等调试端点使用。
     * <p>Redis 不可用或 offline 关闭时返回 0。
     */
    public long offlineFlowCount(String flowId) {
        if (!isEnabled() || flowId == null) return 0L;
        try {
            return producer.offlineFlowSize(flowId);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * B3：查询某 player 兜底 stream 中的离线消息条数（包含历史 game push 的兜底）。
     * <p>Redis 不可用或 offline 关闭时返回 0。
     */
    public long offlinePlayerCount(long playerId) {
        if (!isEnabled()) return 0L;
        try {
            return producer.getOfflineMessageCount(playerId);
        } catch (Exception e) {
            return 0L;
        }
    }

    // ==================== 内部 helper ====================

    private boolean isEnabled() {
        GateConfig.OfflineConfig cfg = offlineCfg();
        return cfg != null && cfg.isEnabled();
    }

    private GateConfig.OfflineConfig offlineCfg() {
        return gateConfig.getFlow().getOffline();
    }

    private Duration retentionTtl() {
        int days = offlineCfg().getRetentionDays();
        if (days <= 0) return Duration.ofDays(7);
        return Duration.ofDays(days);
    }

    private void incrCounter(String name, Tags tags, long amount) {
        if (meterRegistry == null || amount <= 0) return;
        Counter.builder(name).tags(tags).register(meterRegistry).increment(amount);
    }

    /** {@link #replayAndMerge} 的返回结构。 */
    public record ReplayResult(int count, long fromSeq, long toSeq) {
        public static ReplayResult empty(long fromSeq) { return new ReplayResult(0, fromSeq, fromSeq); }
    }
}

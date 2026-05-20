package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 网关进程内的 FlowSession 索引与生命周期管理（design.md §6 / specs/gate-resume-reconnect/spec.md）。
 *
 * <p>三类索引：{@code flowId → FlowSession}、{@code playerId → flowId}、与 Channel 的反向 attribute
 * 由 {@link FlowSession#FLOW_SESSION_KEY} 承担。所有写操作通过 {@code synchronized (this)} 串行，
 * 避免与同一玩家的并发 NEW / RESUME / channelInactive 互相踩。
 *
 * <p>对外暴露三个核心入口：
 * <ul>
 *   <li>{@link #newFlow(long, int, Channel)} — NEW 路径（顶号 + Lua 原子替换）；</li>
 *   <li>{@link #resume(String, long, long, Channel)} — RESUME 校验 + 换绑；</li>
 *   <li>{@link #markDetached(Channel)} — Channel 关闭时调用，置 DETACHED 等 TTL；</li>
 * </ul>
 *
 * <p>定时器：单线程 {@link ScheduledExecutorService} 完成 DETACHED 超时扫描与 Redis 续期。
 */
@Service
public class FlowSessionManager {

    private static final Logger logger = LoggerFactory.getLogger(FlowSessionManager.class);
    private static final Logger flowEventLogger = LoggerFactory.getLogger("gate.flow.event");
    /**
     * Phase B 后顶层独立的跨实例事件 logger（{@code add-flow-observability-buckets}）。
     * 与 {@code gate.flow.event} 平级，便于 ELK pipeline 拆 appender。
     * <p>实际是否写日志由 {@link FlowMetrics#isCrossLoggerEnabled()} 控制。
     */
    private static final Logger crossEventLogger = LoggerFactory.getLogger("gate.cross.event");

    /** Channel 上下文中绑定的 playerId，便于 markDetached(channel) 反查。 */
    public static final AttributeKey<Long> CHANNEL_PLAYER_ID_KEY =
            AttributeKey.valueOf("flowChannelPlayerId");

    private final GateConfig gateConfig;
    private final GateConfig.FlowConfig flowConfig;
    private final RedisFlowStore redisStore;
    private final MeterRegistry meterRegistry;
    /**
     * Phase B 后承接 5 个新指标（{@code gate_flow_takeover_total} 等）的封装。
     * 不为 {@code null}（meterRegistry 为 null 时为 noop 实例）。
     */
    private final FlowMetrics flowMetrics;
    /** B1：服务端通告的 features 位（启动时由配置计算）。 */
    private final int serverAdvertisedFeatures;
    /** B2：跨实例迁移成功后发布 evict 事件；可选注入（单测环境可为 null）。 */
    private volatile FlowEvictPublisher evictPublisher;
    /** B3：离线消息合流；可选注入（单测环境可为 null，避免 OfflineMessageService 依赖）。 */
    private volatile com.clawai.gatedemo.gate.service.OfflineMessageService offlineMessageService;

    private final ConcurrentMap<String, FlowSession> flowsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, String> flowIdByPlayer = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> renewalTask;
    private ScheduledFuture<?> scanTask;

    public FlowSessionManager(GateConfig gateConfig,
                              RedisFlowStore redisStore,
                              MeterRegistry meterRegistry) {
        this.gateConfig = gateConfig;
        this.flowConfig = gateConfig.getFlow();
        this.redisStore = redisStore;
        this.meterRegistry = meterRegistry;
        this.flowMetrics = meterRegistry != null
                ? new FlowMetrics(meterRegistry, flowConfig.getObservability())
                : FlowMetrics.noop();
        GateConfig.FeaturesConfig fc = this.flowConfig.getFeatures();
        this.serverAdvertisedFeatures = FlowFeatures.buildServerAdvertised(
                fc != null && fc.isAdvertiseGwSeq(),
                fc != null && fc.isAdvertiseReplay());
    }

    /** 测试 / 调试：返回内部 {@link FlowMetrics} 句柄。 */
    public FlowMetrics flowMetrics() {
        return flowMetrics;
    }

    /** B1：暴露给 handler，用于写入 AuthResponse.server_features 的 fallback。 */
    public int serverAdvertisedFeatures() {
        return serverAdvertisedFeatures;
    }

    /**
     * B2：注入 {@link FlowEvictPublisher}（构造时为 null，由 setter 装配，便于测试）。
     * Spring 通过 @Autowired(required=false) 自动调用；测试可手动 set。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvictPublisher(FlowEvictPublisher evictPublisher) {
        this.evictPublisher = evictPublisher;
    }

    /**
     * B3：注入 {@link com.clawai.gatedemo.gate.service.OfflineMessageService}（lazy，避免循环依赖）。
     * 单测环境可不设，所有 offline 相关分支会跳过。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setOfflineMessageService(
            @org.springframework.context.annotation.Lazy com.clawai.gatedemo.gate.service.OfflineMessageService offlineMessageService) {
        this.offlineMessageService = offlineMessageService;
    }

    @PostConstruct
    void init() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-flow-scheduler");
            t.setDaemon(true);
            return t;
        });

        int renewalSec = Math.max(1, flowConfig.getRenewalIntervalSeconds());
        int scanSec = Math.max(1, flowConfig.getDetachedScanIntervalSeconds());

        this.renewalTask = scheduler.scheduleAtFixedRate(this::renewAttachedSafe,
                renewalSec, renewalSec, TimeUnit.SECONDS);
        this.scanTask = scheduler.scheduleAtFixedRate(this::scanDetachedSafe,
                scanSec, scanSec, TimeUnit.SECONDS);

        if (meterRegistry != null) {
            io.micrometer.core.instrument.Gauge.builder("gate_flow_active", flowsById, Map::size)
                    .description("Current FlowSession count (ATTACHED + DETACHED)")
                    .register(meterRegistry);
            io.micrometer.core.instrument.Gauge.builder("gate_flow_attached", this,
                            mgr -> mgr.flowsById.values().stream()
                                    .filter(f -> f.getState() == FlowSession.State.ATTACHED)
                                    .count())
                    .description("Currently ATTACHED FlowSession count")
                    .register(meterRegistry);
        }
        logger.info("FlowSessionManager initialized: detachedTtl={}s, maxTtl={}s, renewal={}s, scan={}s",
                flowConfig.getDetachedTtlSeconds(), flowConfig.getMaxTtlSeconds(),
                renewalSec, scanSec);
        logObservabilitySummary();
    }

    /** 启动时打印 observability 配置摘要 + 关键 metric 名，便于多环境部署核对。 */
    private void logObservabilitySummary() {
        GateConfig.ObservabilityConfig oc = flowConfig.getObservability();
        if (oc == null) {
            logger.info("event=flow.observability.summary enabled=false (no config)");
            return;
        }
        logger.info("event=flow.observability.summary enabled={} takeoverTotal={} resumeTotal={} replayTotal={} latency={} crossLogger={} slo={} timeoutMs={} metrics=[{},{},{},{},{}]",
                oc.isEnabled(), oc.isTakeoverTotalEnabled(), oc.isResumeTotalEnabled(),
                oc.isReplayTotalEnabled(), oc.isLatencyEnabled(), oc.isCrossLoggerEnabled(),
                flowMetrics.sloBuckets(), oc.getResumeTimeoutMs(),
                FlowMetrics.M_TAKEOVER_TOTAL, FlowMetrics.M_RESUME_TOTAL,
                FlowMetrics.M_REPLAY_TOTAL, FlowMetrics.M_LATENCY_RESUME,
                FlowMetrics.M_LATENCY_CROSS_READY);
    }

    @PreDestroy
    void shutdown() {
        if (renewalTask != null) renewalTask.cancel(false);
        if (scanTask != null) scanTask.cancel(false);
        if (scheduler != null) scheduler.shutdownNow();
    }

    // ==================== 公共入口 ====================

    /**
     * 创建新 flow（NEW 路径）：mint flowId、原子顶替该 playerId 旧 flow、绑定到指定 Channel。
     *
     * <p>Redis 不可用时降级为「纯内存 flow」：仍 mint 新 flowId、关旧 Channel，但 RESUME 在下次断线后
     * 将一律按 NEW 走（无法跨重启恢复），并打告警日志 + metric。
     *
     * @return 新创建的 FlowSession，永不为 {@code null}
     */
    public synchronized FlowSession newFlow(long playerId, int gameId, Channel channel) {
        return newFlow(playerId, gameId, channel, 0);
    }

    /**
     * B1：带 features 协商的 NEW 路径。负载策略与无 features 版本一致，
     * 协商结果（{@code clientFeatures & serverAdvertisedFeatures}）写入 {@link FlowSession#getFeatures()}。
     * 若协商后包含 {@link FlowFeatures#GW_SEQ}，按配置为 session 装配 {@link DownstreamBuffer}。
     */
    public synchronized FlowSession newFlow(long playerId, int gameId, Channel channel, int clientFeatures) {
        long startedNanos = System.nanoTime();
        FlowSession session = newFlow0(playerId, gameId, channel, clientFeatures);
        // 普通 NEW（非降级路径）→ outcome=succeeded
        flowMetrics.resumeTotal(FlowMetrics.KIND_NEW, FlowMetrics.OUTCOME_SUCCEEDED).increment();
        flowMetrics.resumeLatency(FlowMetrics.KIND_NEW)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        return session;
    }

    /**
     * 内部实现：与 outcome metric 解耦，让 {@link #newFlow} 与 {@link #newFlowAfterReject}
     * 分别 emit {@code OUTCOME_SUCCEEDED} / {@code OUTCOME_DEGRADED_TO_NEW}（互斥），
     * 同一 NEW 不会在 {@code gate_flow_resume_total} 上双计数。
     */
    private synchronized FlowSession newFlow0(long playerId, int gameId, Channel channel, int clientFeatures) {
        Objects.requireNonNull(channel, "channel");
        long now = System.currentTimeMillis();
        long expiresAt = now + flowConfig.getDetachedTtlSeconds() * 1000L;
        String newFlowId = UUID.randomUUID().toString();
        String gateId = gateConfig.getId();

        RedisFlowRecord record = new RedisFlowRecord(
                newFlowId, playerId, gameId, gateId,
                now, expiresAt, 0L, 0L);

        RedisFlowStore.TakeoverResult takeover = redisStore.takeover(record);
        boolean redisOk = takeover.ok();
        String evictedFlowId = takeover.evictedFlowId();
        // 即使 Redis 没返回旧 flow（首次登录 / Redis 故障），本地仍可能持有上次 NEW 的内存对象，
        // 必须强制关闭它，避免「老 Channel 仍 ATTACHED + 新 Channel 已绑」的双活态。
        if (evictedFlowId == null) {
            String previousLocal = flowIdByPlayer.get(playerId);
            if (previousLocal != null) {
                evictedFlowId = previousLocal;
            }
        }

        evictLocal(playerId, evictedFlowId, "new_takeover", channel);

        FlowSession session = new FlowSession(newFlowId, playerId, gameId, gateId,
                now, expiresAt, channel);
        int negotiated = FlowFeatures.negotiate(clientFeatures, serverAdvertisedFeatures);
        session.setFeatures(negotiated);
        if (shouldEnableBuffer(negotiated)) {
            session.setBuffer(createBuffer());
        }
        flowsById.put(newFlowId, session);
        flowIdByPlayer.put(playerId, newFlowId);
        channel.attr(CHANNEL_PLAYER_ID_KEY).set(playerId);

        emitCounter("new", redisOk ? "ok" : "redis_unavailable");
        emitEvent("flow.new", session,
                "ageMs=0,features=0x" + Integer.toHexString(negotiated));
        return session;
    }

    /**
     * NEW 降级：在 RESUME 校验失败时调用，与 {@link #newFlow} 共用顶替逻辑，但 metrics reason 区分。
     */
    public synchronized FlowSession newFlowAfterReject(long playerId, int gameId, Channel channel,
                                                       FlowResumeOutcome rejectReason) {
        return newFlowAfterReject(playerId, gameId, channel, rejectReason, 0);
    }

    /** B1：带 features 的版本。 */
    public synchronized FlowSession newFlowAfterReject(long playerId, int gameId, Channel channel,
                                                       FlowResumeOutcome rejectReason,
                                                       int clientFeatures) {
        long startedNanos = System.nanoTime();
        FlowSession session = newFlow0(playerId, gameId, channel, clientFeatures);
        emitCounter("new", "resume_rejected_" + rejectReason.name().toLowerCase());
        // 降级 NEW（RESUME 失败后回退）→ outcome=degraded_to_new（与普通 NEW succeeded 互斥）
        flowMetrics.resumeTotal(FlowMetrics.KIND_NEW, FlowMetrics.OUTCOME_DEGRADED_TO_NEW).increment();
        flowMetrics.resumeLatency(FlowMetrics.KIND_NEW)
                .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
        return session;
    }

    /**
     * RESUME 路径：校验通过则换绑 Channel，返回 ({@link FlowResumeOutcome#RESUMED}, FlowSession)；
     * 否则返回 ({@code REJECTED_*}, null)，由调用方降级 NEW。
     */
    public synchronized ResumeResult resume(String flowId, long playerIdFromToken,
                                            long lastClientRecvSeq, Channel channel) {
        return resume(flowId, playerIdFromToken, lastClientRecvSeq, channel, 0);
    }

    /**
     * B1：带 features 协商的 RESUME 路径。校验通过后：
     * <ol>
     *   <li>换绑 Channel；</li>
     *   <li>按 negotiated features 装/重置 buffer（如客户端首次声明 GW_SEQ）；</li>
     *   <li>若 {@link FlowFeatures#RESUME_REPLAY} 协商通过，在返回前调用 {@link #replayPending} 重写 (lastClientRecvSeq, latest] 区间帧；</li>
     *   <li>裁剪 buffer：客户端宣称已收到 lastClientRecvSeq，先 ackUpTo。</li>
     * </ol>
     */
    public synchronized ResumeResult resume(String flowId, long playerIdFromToken,
                                            long lastClientRecvSeq, Channel channel,
                                            int clientFeatures) {
        Objects.requireNonNull(channel, "channel");
        if (flowId == null || flowId.isBlank()) {
            flowMetrics.resumeTotal(FlowMetrics.KIND_SAME_GW, FlowMetrics.OUTCOME_REJECTED_EXPIRED).increment();
            return new ResumeResult(FlowResumeOutcome.REJECTED_EXPIRED, null, 0, 0);
        }

        long started = System.nanoTime();
        // resume kind 在 cross-takeover 分支可能变为 cross_gw；先按 same_gw 起步，跨实例时改写
        String kind = FlowMetrics.KIND_SAME_GW;
        String outcomeForMetric = FlowMetrics.OUTCOME_SUCCEEDED;
        boolean resumeReturned = false;
        try {
            FlowSession local = flowsById.get(flowId);
            RedisFlowRecord record = redisStore.loadByFlowId(flowId);
            long now = System.currentTimeMillis();

            if (record == null && local == null) {
                emitCounter("resume_rejected", "expired");
                outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_EXPIRED;
                resumeReturned = true;
                return new ResumeResult(FlowResumeOutcome.REJECTED_EXPIRED, null, 0, 0);
            }
            if (record != null && record.expiresAt() <= now) {
                emitCounter("resume_rejected", "expired");
                outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_EXPIRED;
                resumeReturned = true;
                return new ResumeResult(FlowResumeOutcome.REJECTED_EXPIRED, null, 0, 0);
            }
            if (record != null && record.playerId() != playerIdFromToken) {
                emitCounter("resume_rejected", "mismatch");
                outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_MISMATCH;
                resumeReturned = true;
                return new ResumeResult(FlowResumeOutcome.REJECTED_MISMATCH, null, 0, 0);
            }

            // B2: 跨实例 owner 迁移 — 用 Lua 原子改写 ownerGateId，旧 owner 通过 Pub/Sub 驱逐。
            String previousOwnerGateId = null;
            boolean crossTookOver = false;
            GateConfig.CrossConfig crossCfg = flowConfig.getCross();
            boolean crossEnabled = crossCfg == null || crossCfg.isEnabled();
            if (record != null && !record.ownerGateId().equals(gateConfig.getId())) {
                kind = FlowMetrics.KIND_CROSS_GW;
                if (!crossEnabled) {
                    emitCounter("resume_rejected", "owner_other");
                    outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_OWNER_OTHER;
                    resumeReturned = true;
                    return new ResumeResult(FlowResumeOutcome.REJECTED_OWNER_OTHER, null, 0, 0);
                }
                long newExpiresAtForLua = now + flowConfig.getDetachedTtlSeconds() * 1000L;
                long maxExpiresAtForLua = record.createdAt() + flowConfig.getMaxTtlSeconds() * 1000L;
                if (newExpiresAtForLua > maxExpiresAtForLua) newExpiresAtForLua = maxExpiresAtForLua;
                RedisFlowStore.CrossTakeoverResult takeoverResult =
                        redisStore.crossTakeover(flowId, playerIdFromToken, gateConfig.getId(), newExpiresAtForLua);
                // 新观测性：result 标签 6 值映射（旧的 gate_flow_total{event=cross_takeover,reason=...} 已下线）
                flowMetrics.takeover(FlowMetrics.mapTakeoverResult(takeoverResult.state())).increment();
                switch (takeoverResult.state()) {
                    case EXPIRED -> {
                        emitCounter("resume_rejected", "expired");
                        outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_EXPIRED;
                        resumeReturned = true;
                        return new ResumeResult(FlowResumeOutcome.REJECTED_EXPIRED, null, 0, 0);
                    }
                    case REDIS_UNAVAILABLE -> {
                        emitCounter("resume_rejected", "owner_other");
                        outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_OWNER_OTHER;
                        resumeReturned = true;
                        return new ResumeResult(FlowResumeOutcome.REJECTED_OWNER_OTHER, null, 0, 0);
                    }
                    case RESUMED_OWNER_SAME -> { /* result=succeeded 已 emit */ }
                    case RESUMED_OWNER_CHANGED -> {
                        previousOwnerGateId = takeoverResult.previousOwnerGateId();
                        crossTookOver = true;
                    }
                }
                flowEventLogger.info("event=flow.cross_takeover flowId={} playerId={} previousOwnerGateId={} newOwnerGateId={} state={}",
                        flowId, playerIdFromToken, takeoverResult.previousOwnerGateId(),
                        gateConfig.getId(), takeoverResult.state());
            }

            if (local == null) {
                // B2 跨实例场景：record.ownerGateId 在 crossTakeover 后已被改写，FlowSession 用本实例 id 重建。
                String ownerForLocal = (record != null && crossTookOver) ? gateConfig.getId() : (record != null ? record.ownerGateId() : gateConfig.getId());
                local = new FlowSession(flowId,
                        record != null ? record.playerId() : playerIdFromToken,
                        record != null ? record.gameId() : 0,
                        ownerForLocal,
                        record != null ? record.createdAt() : now,
                        record != null ? record.expiresAt() : (now + flowConfig.getDetachedTtlSeconds() * 1000L),
                        null);
                // B3：本地无缓存的 FlowSession（跨实例 RESUME / TTL 边角恢复）时，
                // nextGwSeq 必须 >= max(record.lastSeqAnchor, offline.maxGwSeq)，否则新 push 会与 offline 既有条目冲突。
                long anchor = record != null ? record.lastSeqAnchor() : 0L;
                long offlineMax = offlineMessageService != null
                        ? offlineMessageService.maxGwSeq(flowId) : 0L;
                long seed = Math.max(anchor, offlineMax);
                if (seed > 0L) {
                    local.seedNextGwSeq(seed);
                }
                flowsById.put(local.getFlowId(), local);
                flowIdByPlayer.put(local.getPlayerId(), local.getFlowId());
            } else if (local.getPlayerId() != playerIdFromToken) {
                emitCounter("resume_rejected", "mismatch");
                outcomeForMetric = FlowMetrics.OUTCOME_REJECTED_MISMATCH;
                resumeReturned = true;
                return new ResumeResult(FlowResumeOutcome.REJECTED_MISMATCH, null, 0, 0);
            }

            int negotiated = FlowFeatures.negotiate(clientFeatures, serverAdvertisedFeatures);
            local.setFeatures(negotiated);
            if (shouldEnableBuffer(negotiated) && local.getBuffer() == null) {
                local.setBuffer(createBuffer());
            }

            Channel oldChannel = local.attach(channel);
            channel.attr(CHANNEL_PLAYER_ID_KEY).set(local.getPlayerId());
            // 客户端宣称已收 lastClientRecvSeq → 提前裁剪 buffer，减少之后重放的体积。
            int trimmed = 0;
            DownstreamBuffer buf = local.getBuffer();
            if (buf != null && lastClientRecvSeq > 0) {
                trimmed = buf.ackUpTo(lastClientRecvSeq);
                // 被 ACK 跳过的 buffer 帧 → source=buffer,outcome=skipped_acked
                if (trimmed > 0) {
                    flowMetrics.replay(FlowMetrics.SOURCE_BUFFER, FlowMetrics.REPLAY_SKIPPED_ACKED)
                            .increment(trimmed);
                }
            }
            local.setLastSeqAnchor(Math.max(local.getLastSeqAnchor(), lastClientRecvSeq));
            long newExpiresAt = now + flowConfig.getDetachedTtlSeconds() * 1000L;
            long maxExpiresAt = local.getCreatedAt() + flowConfig.getMaxTtlSeconds() * 1000L;
            if (newExpiresAt > maxExpiresAt) {
                newExpiresAt = maxExpiresAt;
            }
            local.setExpiresAt(newExpiresAt);
            redisStore.renew(local.getFlowId(), local.getPlayerId(), newExpiresAt);

            if (oldChannel != null && oldChannel != channel && oldChannel.isActive()) {
                logger.info("RESUME closing stale channel for flowId={}", local.getFlowId());
                oldChannel.close();
            }

            int replayedCount = 0;
            long replayToSeq = lastClientRecvSeq;
            if (FlowFeatures.supportsResumeReplay(negotiated) && buf != null) {
                ReplayResult rr = replayPending(local, channel, lastClientRecvSeq);
                replayedCount = rr.count();
                replayToSeq = rr.toSeq();
            }

            // B3：合流离线 stream（buffer 重放完毕之后），从 max(lastClientRecvSeq, replayToSeq) 开始
            int offlineReplayed = 0;
            long offlineFromSeq = Math.max(lastClientRecvSeq, replayToSeq);
            long offlineToSeq = offlineFromSeq;
            if (offlineMessageService != null) {
                com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult or =
                        offlineMessageService.replayAndMerge(local, channel, offlineFromSeq);
                offlineReplayed = or.count();
                offlineToSeq = or.toSeq();
            }
            int totalReplayed = replayedCount + offlineReplayed;
            long finalReplayToSeq = Math.max(replayToSeq, offlineToSeq);

            // B2: 跨实例迁移成功，PUBLISH 通知老 gate 关闭其本地 Channel；publisher 可为 null（无 Spring 注入时）
            if (crossTookOver && evictPublisher != null) {
                evictPublisher.publish(local.getFlowId(), gateConfig.getId(), previousOwnerGateId);
            }

            // 跨实例 RESUME 成功末尾：emit 合并汇总（source=merged）+ ready latency
            if (crossTookOver) {
                if (totalReplayed > 0) {
                    flowMetrics.replay(FlowMetrics.SOURCE_MERGED, FlowMetrics.REPLAY_REPLAYED)
                            .increment(totalReplayed);
                }
                flowMetrics.crossReadyLatency()
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
                if (flowMetrics.isCrossLoggerEnabled()) {
                    crossEventLogger.info(
                            "event=cross.takeover.result flowId={} playerId={} previousOwnerGateId={} newOwnerGateId={} result=succeeded ageMs={} bufferReplayed={} offlineReplayed={}",
                            local.getFlowId(), local.getPlayerId(), previousOwnerGateId,
                            gateConfig.getId(), (now - local.getCreatedAt()),
                            replayedCount, offlineReplayed);
                }
            }

            emitCounter("resumed", "ok");
            emitEvent("flow.resumed", local,
                    "ageMs=" + (now - local.getCreatedAt())
                            + ",lastSeq=" + lastClientRecvSeq
                            + ",trimmedOnResume=" + trimmed
                            + ",bufferReplayed=" + replayedCount
                            + ",offlineReplayed=" + offlineReplayed
                            + ",features=0x" + Integer.toHexString(negotiated)
                            + (crossTookOver ? ",crossTakeover=true,previousOwnerGateId=" + previousOwnerGateId : ""));
            outcomeForMetric = FlowMetrics.OUTCOME_SUCCEEDED;
            resumeReturned = true;
            return new ResumeResult(FlowResumeOutcome.RESUMED, local, totalReplayed, finalReplayToSeq);
        } catch (RuntimeException re) {
            // channel 已 inactive 时通常会触发底层异常 / IllegalStateException；归类为 cancelled
            if (!channel.isActive()) {
                outcomeForMetric = FlowMetrics.OUTCOME_CANCELLED;
                resumeReturned = true;
                logger.warn("RESUME aborted due to inactive channel flowId={}: {}", flowId, re.getMessage());
                return new ResumeResult(FlowResumeOutcome.REJECTED_EXPIRED, null, 0, 0);
            }
            throw re;
        } finally {
            long elapsedNanos = System.nanoTime() - started;
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
            // 仅在成功路径上检查 timeout（rejected/cancelled 已是失败结果，不再二次标记）
            if (resumeReturned && outcomeForMetric.equals(FlowMetrics.OUTCOME_SUCCEEDED)
                    && elapsedMs > flowMetrics.resumeTimeoutMs()) {
                outcomeForMetric = FlowMetrics.OUTCOME_TIMEOUT;
            }
            if (resumeReturned) {
                flowMetrics.resumeTotal(kind, outcomeForMetric).increment();
                flowMetrics.resumeLatency(kind).record(elapsedNanos, TimeUnit.NANOSECONDS);
            }
        }
    }

    /**
     * Channel 关闭钩子：若该 Channel 仍是 flow 的当前绑定，置 DETACHED；否则忽略（防止抢绑覆盖）。
     */
    public void markDetached(Channel channel) {
        FlowSession session = channel.attr(FlowSession.FLOW_SESSION_KEY).get();
        if (session == null) return;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (session.detachIfMatches(channel, now)) {
                redisStore.markDetached(session.getFlowId(), now);
                emitCounter("detached", "channel_inactive");
                emitEvent("flow.detached", session,
                        "ageMs=" + (now - session.getCreatedAt()));
            }
        }
    }

    /**
     * B2：响应跨实例 owner 迁移的 evict 事件。仅清本地，不动 Redis（Redis 已被新 owner 改写）。
     * <p>B3：evict 前若 {@code gate.flow.offline.flush-on-cross-evict=true} 则把 buffer 中未 ACK 帧
     * flush 到 offline stream，避免跨实例漂移导致丢消息。
     *
     * @return 是否实际驱逐了一个本地 flow（false 表示本地不知道该 flow / 已被其它操作清理）
     */
    public boolean evictByCrossInstanceTakeover(String flowId, String newOwnerGateId) {
        if (flowId == null) return false;
        synchronized (this) {
            FlowSession removed = flowsById.remove(flowId);
            if (removed == null) return false;
            // B3：先 flush 未 ACK buffer 到 offline stream（同步执行；失败仅 warn 不阻塞 evict）
            if (offlineMessageService != null
                    && flowConfig.getOffline() != null
                    && flowConfig.getOffline().isFlushOnCrossEvict()) {
                try {
                    offlineMessageService.flushBufferToOffline(removed, "cross_takeover");
                } catch (Throwable t) {
                    logger.warn("flushBufferToOffline failed during cross-evict flowId={}: {}",
                            flowId, t.getMessage());
                }
            }
            String mapped = flowIdByPlayer.get(removed.getPlayerId());
            if (flowId.equals(mapped)) {
                flowIdByPlayer.remove(removed.getPlayerId());
            }
            Channel ch = removed.getCurrentChannel();
            if (ch != null && ch.isActive()) {
                logger.info("Closing stale channel due to cross-instance takeover: flowId={} newOwnerGateId={}",
                        flowId, newOwnerGateId);
                ch.close();
            }
            emitCounter("destroyed", "cross_takeover");
            emitEvent("flow.destroyed", removed,
                    "reason=cross_takeover,newOwnerGateId=" + newOwnerGateId
                            + ",ageMs=" + (System.currentTimeMillis() - removed.getCreatedAt()));
            // 跨实例独立 logger（add-flow-observability-buckets）
            if (flowMetrics.isCrossLoggerEnabled()) {
                crossEventLogger.info(
                        "event=cross.evict.received flowId={} playerId={} newOwnerGateId={} localOwner={} ageMs={}",
                        flowId, removed.getPlayerId(), newOwnerGateId,
                        gateConfig.getId(), (System.currentTimeMillis() - removed.getCreatedAt()));
            }
            return true;
        }
    }

    /** 显式销毁（顶号 / TTL 超时 / 关停）。线程安全：由调用方持外部锁或直接调用。
     *
     * <p>B3：reason ∈ {@code detached_ttl} / {@code max_ttl} 且 {@code flush-on-destroy=true} 时，
     * 先把 buffer 中未 ACK 帧 flush 到 offline stream。{@code new_takeover}（玩家主动重登）不 flush —
     * 老 buffer 对新 flow 无意义。
     */
    public synchronized void destroy(FlowSession session, String reason) {
        if (session == null) return;
        FlowSession removed = flowsById.remove(session.getFlowId());
        if (removed != null) {
            // B3：TTL 边界 flush 未 ACK buffer 到 offline stream
            if (offlineMessageService != null
                    && flowConfig.getOffline() != null
                    && flowConfig.getOffline().isFlushOnDestroy()
                    && ("detached_ttl".equals(reason) || "max_ttl".equals(reason))) {
                try {
                    offlineMessageService.flushBufferToOffline(removed, reason);
                } catch (Throwable t) {
                    logger.warn("flushBufferToOffline failed during destroy flowId={} reason={}: {}",
                            session.getFlowId(), reason, t.getMessage());
                }
            }
            // 仅当 byplayer 索引仍指向自己才移除
            String mapped = flowIdByPlayer.get(session.getPlayerId());
            if (session.getFlowId().equals(mapped)) {
                flowIdByPlayer.remove(session.getPlayerId());
            }
            redisStore.destroy(session.getFlowId(), session.getPlayerId());
            emitCounter("destroyed", reason);
            emitEvent("flow.destroyed", session,
                    "reason=" + reason + ",ageMs="
                            + (System.currentTimeMillis() - session.getCreatedAt()));
        }
    }

    // ==================== 查询接口（供 PlayerService 委托） ====================

    public FlowSession getByPlayerId(long playerId) {
        String flowId = flowIdByPlayer.get(playerId);
        return flowId != null ? flowsById.get(flowId) : null;
    }

    public FlowSession getByFlowId(String flowId) {
        return flowsById.get(flowId);
    }

    public boolean hasAttachedChannel(long playerId) {
        FlowSession s = getByPlayerId(playerId);
        return s != null && s.getState() == FlowSession.State.ATTACHED
                && s.getCurrentChannel() != null && s.getCurrentChannel().isActive();
    }

    public int totalFlows() { return flowsById.size(); }

    public Collection<FlowSession> snapshot() { return new ArrayList<>(flowsById.values()); }

    public Set<Long> attachedPlayerIds() {
        return flowsById.values().stream()
                .filter(f -> f.getState() == FlowSession.State.ATTACHED)
                .map(FlowSession::getPlayerId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ==================== 内部调度 ====================

    private void renewAttachedSafe() {
        try {
            long now = System.currentTimeMillis();
            int attachedTtlMs = flowConfig.getDetachedTtlSeconds() * 1000;
            for (FlowSession s : flowsById.values()) {
                if (s.getState() != FlowSession.State.ATTACHED) continue;
                long newExpiresAt = now + attachedTtlMs;
                long maxExpiresAt = s.getCreatedAt() + flowConfig.getMaxTtlSeconds() * 1000L;
                if (newExpiresAt > maxExpiresAt) newExpiresAt = maxExpiresAt;
                s.setExpiresAt(newExpiresAt);
                redisStore.renew(s.getFlowId(), s.getPlayerId(), newExpiresAt);
            }
        } catch (Throwable t) {
            logger.warn("renewAttached failed: {}", t.getMessage());
        }
    }

    private void scanDetachedSafe() {
        try {
            long now = System.currentTimeMillis();
            long detachedThresholdMs = flowConfig.getDetachedTtlSeconds() * 1000L;
            long maxTtlMs = flowConfig.getMaxTtlSeconds() * 1000L;
            List<FlowSession> toDestroy = new ArrayList<>();
            for (FlowSession s : flowsById.values()) {
                boolean overTtl =
                        s.getState() == FlowSession.State.DETACHED
                                && (now - s.getDetachedAt()) >= detachedThresholdMs;
                boolean overMax = (now - s.getCreatedAt()) >= maxTtlMs;
                if (overTtl || overMax) {
                    toDestroy.add(s);
                }
            }
            for (FlowSession s : toDestroy) {
                destroy(s, s.getState() == FlowSession.State.DETACHED ? "detached_ttl" : "max_ttl");
            }
        } catch (Throwable t) {
            logger.warn("scanDetached failed: {}", t.getMessage());
        }
    }

    // ==================== 内部帮助 ====================

    private void evictLocal(long playerId, String previousFlowId, String reason, Channel newChannel) {
        if (previousFlowId == null) return;
        FlowSession previous = flowsById.remove(previousFlowId);
        if (previous != null) {
            Channel oldChannel = previous.getCurrentChannel();
            if (oldChannel != null && oldChannel != newChannel && oldChannel.isActive()) {
                logger.info("NEW takeover closing old channel for playerId={} flowId={}",
                        playerId, previousFlowId);
                oldChannel.close();
            }
            emitEvent("flow.destroyed", previous,
                    "reason=" + reason + ",ageMs="
                            + (System.currentTimeMillis() - previous.getCreatedAt()));
            emitCounter("destroyed", reason);
        }
        // byplayer 索引会被新 flow 覆盖；若 previous 仍是当前映射，移除
        if (previousFlowId.equals(flowIdByPlayer.get(playerId))) {
            flowIdByPlayer.remove(playerId);
        }
    }

    private void emitCounter(String event, String reason) {
        if (meterRegistry == null) return;
        Counter.builder("gate_flow_total")
                .tags(Tags.of(Tag.of("event", event), Tag.of("reason", reason == null ? "ok" : reason)))
                .register(meterRegistry)
                .increment();
    }

    private void emitEvent(String event, FlowSession session, String extra) {
        flowEventLogger.info("event={} flowId={} playerId={} gameId={} ownerGateId={} {}",
                event, session.getFlowId(), session.getPlayerId(), session.getGameId(),
                session.getOwnerGateId(), extra == null ? "" : extra);
    }

    /**
     * RESUME 返回包：当 {@code outcome != RESUMED} 时 {@code session} 必为 {@code null}，
     * 调用方应走 {@link #newFlowAfterReject} 降级。
     *
     * <p>B1 新增 {@code replayedCount} 与 {@code replayToSeq}：用于日志 / 监控反馈本次 RESUME 共重放了多少帧。
     */
    public record ResumeResult(FlowResumeOutcome outcome, FlowSession session,
                               int replayedCount, long replayToSeq) {
        public boolean isResumed() { return outcome == FlowResumeOutcome.RESUMED; }
    }

    /**
     * B1：{@link #replayPending} 的返回结构，便于上层记录日志与 metric。
     */
    public record ReplayResult(int count, long fromSeq, long toSeq) {}

    // ==================== B1：下行 buffer / ACK / 重放 ====================

    /**
     * B1：网关下行帧的统一写入入口。
     *
     * <p>若 session.features 协商了 {@link FlowFeatures#GW_SEQ}：派号 → 标 FLAG_HAS_GW_SEQ →
     * 入 buffer → 写 Channel；否则透传 {@code channel.writeAndFlush(message)}。
     *
     * <p>{@code session} 为 {@code null} 表示该玩家不存在 ATTACHED 的 FlowSession（DETACHED / 已销毁）：
     * 调用方应改走离线消息路径。
     *
     * @return 是否成功 enqueue + write；false 表示 force_detach overflow（调用方已被关连接）
     */
    public boolean writeDownstream(FlowSession session, WrappedMessage message) {
        Objects.requireNonNull(message, "message");
        if (session == null) return false;
        Channel channel = session.getCurrentChannel();
        if (channel == null || !channel.isActive()) return false;

        if (!FlowFeatures.supportsGwSeq(session.getFeatures())) {
            channel.writeAndFlush(message);
            return true;
        }

        DownstreamBuffer buf = session.getBuffer();
        long seq = session.incrementAndGetGwSeq();
        MessageHeader header = message.getHeader();
        if (header == null) {
            throw new IllegalArgumentException("WrappedMessage.header must not be null when writing downstream");
        }
        header.setHasGwSeq(true);
        header.setGwSeq(seq);

        if (buf != null) {
            int bodyLen = message.getBody() != null ? message.getBody().toBytes().length : 0;
            // 估值 = 16 字节头 + 8 字节 gwSeq + body；压缩后实际更小，估值用于容量上限。
            int sizeBytes = 16 + 8 + bodyLen;
            long nowMs = System.currentTimeMillis();
            DownstreamBuffer.EnqueueResult er = buf.enqueue(seq, message, sizeBytes, nowMs);
            switch (er) {
                case DROPPED_OLDEST -> {
                    emitCounter("buffer_dropped", "drop_oldest");
                    flowEventLogger.warn("event=flow.buffer.overflow reason=drop_oldest flowId={} playerId={} droppedAtSeq={}",
                            session.getFlowId(), session.getPlayerId(), seq);
                }
                case FORCE_DETACH -> {
                    emitCounter("buffer_overflow", "force_detach");
                    flowEventLogger.warn("event=flow.buffer.overflow reason=force_detach flowId={} playerId={} atSeq={}",
                            session.getFlowId(), session.getPlayerId(), seq);
                    channel.close();
                    return false;
                }
                case OK -> { /* no-op */ }
            }
        }
        channel.writeAndFlush(message);
        return true;
    }

    /** B1：按 playerId 派发下行（PlayerService.sendToPlayer 内部使用）。 */
    public boolean writeDownstream(long playerId, WrappedMessage message) {
        FlowSession session = getByPlayerId(playerId);
        return writeDownstream(session, message);
    }

    /**
     * B1：客户端通过 ClientHeartbeat 上报已收到的最大 gwSeq；裁剪 buffer + 续写 Redis 锚点。
     *
     * @return 实际从 buffer 中丢弃的 entry 数（含 0）
     */
    public int ackSeq(long playerId, long ackedSeq) {
        if (ackedSeq <= 0) return 0;
        FlowSession session = getByPlayerId(playerId);
        if (session == null) return 0;
        int trimmed = 0;
        DownstreamBuffer buf = session.getBuffer();
        if (buf != null) {
            trimmed = buf.ackUpTo(ackedSeq);
        }
        long previous = session.getLastSeqAnchor();
        if (ackedSeq > previous) {
            session.setLastSeqAnchor(ackedSeq);
            redisStore.markAckedSeq(session.getFlowId(), ackedSeq);
        }
        if (trimmed > 0 && meterRegistry != null) {
            Counter.builder("gate_flow_buffer_ack_trimmed_total")
                    .register(meterRegistry)
                    .increment(trimmed);
        }
        return trimmed;
    }

    /**
     * B1：在新 Channel 上按 gwSeq 升序补写 buffer 中 (fromSeq, latest] 范围的 entry。
     * 不修改 buffer 内容（裁剪由 ACK 触发）。
     *
     * <p>注意 ACK 已经把 ≤fromSeq 的 entry 裁掉，但 buffer 可能因 drop_oldest 失去更早 entry —
     * 此时实际 fromSeq 会被推到 buffer.minGwSeq - 1，由日志 / metric 体现 gap。
     */
    public ReplayResult replayPending(FlowSession session, Channel channel, long fromSeq) {
        DownstreamBuffer buf = session.getBuffer();
        if (buf == null || buf.size() == 0) {
            return new ReplayResult(0, fromSeq, fromSeq);
        }
        java.util.List<DownstreamBuffer.BufferedFrame> pending = buf.drain(fromSeq);
        if (pending.isEmpty()) {
            return new ReplayResult(0, fromSeq, fromSeq);
        }
        long actualFrom = pending.get(0).gwSeq() - 1; // 实际重放起点 - 1
        long actualTo = pending.get(pending.size() - 1).gwSeq();
        // channel 在 RESUME 期间被 client 断开 → 把剩余帧记为 dropped
        if (!channel.isActive()) {
            flowMetrics.replay(FlowMetrics.SOURCE_BUFFER, FlowMetrics.REPLAY_DROPPED)
                    .increment(pending.size());
            flowEventLogger.warn("event=flow.buffer.replay.dropped flowId={} playerId={} count={} reason=channel_inactive",
                    session.getFlowId(), session.getPlayerId(), pending.size());
            return new ReplayResult(0, fromSeq, fromSeq);
        }
        for (DownstreamBuffer.BufferedFrame f : pending) {
            channel.write(f.frame());
        }
        channel.flush();

        // 新观测性：source=buffer, outcome=replayed（旧的 gate_flow_buffer_replay_total 已下线）
        flowMetrics.replay(FlowMetrics.SOURCE_BUFFER, FlowMetrics.REPLAY_REPLAYED)
                .increment(pending.size());
        flowEventLogger.info("event=flow.buffer.replay flowId={} playerId={} fromSeq={} toSeq={} count={} requestedFromSeq={}",
                session.getFlowId(), session.getPlayerId(), actualFrom, actualTo, pending.size(), fromSeq);
        return new ReplayResult(pending.size(), actualFrom, actualTo);
    }

    /**
     * Package-friendly：供 {@link com.clawai.gatedemo.gate.service.OfflineMessageService} emit
     * {@code gate_flow_replay_total{source=offline, outcome=...}}。
     *
     * <p>实施动机：B3 OfflineMessageService 当前 emit 的是 B3 自己的 metric
     * （{@code gate_flow_offline_replayed_total} 等），与本变更的 {@code gate_flow_replay_total}
     * 是不同视角；为避免 OfflineMessageService 直接持有 {@link FlowMetrics} 双份引用，统一从此入口转。
     *
     * @param source  {@code buffer} / {@code offline} / {@code merged}
     * @param outcome {@code replayed} / {@code skipped_acked} / {@code dropped}
     * @param count   要 increment 的数量（≤0 时静默忽略）
     */
    public void recordReplay(String source, String outcome, int count) {
        if (count <= 0) return;
        flowMetrics.replay(source, outcome).increment(count);
    }

    private boolean shouldEnableBuffer(int negotiatedFeatures) {
        return FlowFeatures.supportsGwSeq(negotiatedFeatures)
                && flowConfig.getBuffer() != null
                && flowConfig.getBuffer().isEnabled();
    }

    private DownstreamBuffer createBuffer() {
        GateConfig.BufferConfig cfg = flowConfig.getBuffer();
        DownstreamBuffer.OverflowPolicy policy = "force_detach".equalsIgnoreCase(cfg.getOverflowPolicy())
                ? DownstreamBuffer.OverflowPolicy.FORCE_DETACH
                : DownstreamBuffer.OverflowPolicy.DROP_OLDEST;
        return new DownstreamBuffer(cfg.getCapacityEntries(), cfg.getCapacityBytes(), policy);
    }

    // ==================== 测试 / 诊断辅助（包级可见） ====================

    Map<Long, String> debugPlayerIndex() {
        return new HashMap<>(flowIdByPlayer);
    }

    void debugForceScanDetached() { scanDetachedSafe(); }
}

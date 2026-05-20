package com.clawai.gatedemo.gate.flow.metrics;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowResumeOutcome;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 集中维护 Phase B 之后的 flow 观测性指标（{@code add-flow-observability-buckets} 变更）。
 *
 * <p>由 {@code FlowSessionManager} 注入；负责：
 * <ul>
 *   <li>5 个新指标的注册 + 句柄缓存（hot path 零反射查表）；</li>
 *   <li>底层枚举（{@link RedisFlowStore.CrossState} / {@link FlowResumeOutcome}）到业务标签值的映射；</li>
 *   <li>per-metric switch（任一关闭 → 返回 noop Counter/Timer）；</li>
 *   <li>{@link #noop()} 工厂供单测 / Redis 不可用兜底。</li>
 * </ul>
 *
 * <p>所有 metric 名 / tag 值 / SLO bucket 来自 {@code openspec/changes/add-flow-observability-buckets/} 中的 spec 与 design。
 *
 * <p><b>非 Spring bean</b>：由每个持有者（{@code FlowSessionManager} / {@code FlowEvictPublisher} /
 * {@code FlowEvictListener}）自行 {@code new FlowMetrics(meterRegistry, gateConfig.getFlow().getObservability())}。
 * Meter 句柄通过 Micrometer 在 registry 层去重，多实例并存只会有 cache 重复，hot path 仍是 O(1)。
 */
public final class FlowMetrics {

    private static final Logger logger = LoggerFactory.getLogger(FlowMetrics.class);

    // ==================== Metric 名常量 ====================

    public static final String M_TAKEOVER_TOTAL = "gate_flow_takeover_total";
    public static final String M_RESUME_TOTAL = "gate_flow_resume_total";
    public static final String M_REPLAY_TOTAL = "gate_flow_replay_total";
    public static final String M_LATENCY_RESUME = "gate_flow_latency_resume_ms";
    public static final String M_LATENCY_CROSS_READY = "gate_flow_latency_cross_ready_ms";

    // ==================== Tag key 常量 ====================

    public static final String T_RESULT = "result";
    public static final String T_KIND = "kind";
    public static final String T_OUTCOME = "outcome";
    public static final String T_SOURCE = "source";

    // ==================== result 标签值（6 值） ====================

    public static final String RESULT_SUCCEEDED = "succeeded";
    public static final String RESULT_METADATA_MISSING = "metadata_missing";
    public static final String RESULT_REDIS_UNAVAILABLE = "redis_unavailable";
    public static final String RESULT_REJECTED = "rejected";
    public static final String RESULT_EVICTED_BY_REMOTE = "evicted_by_remote";
    public static final String RESULT_EVICTED_REMOTE = "evicted_remote";

    public static final List<String> ALL_RESULTS = List.of(
            RESULT_SUCCEEDED, RESULT_METADATA_MISSING, RESULT_REDIS_UNAVAILABLE,
            RESULT_REJECTED, RESULT_EVICTED_BY_REMOTE, RESULT_EVICTED_REMOTE);

    // ==================== kind 标签值（3 值） ====================

    public static final String KIND_SAME_GW = "same_gw";
    public static final String KIND_CROSS_GW = "cross_gw";
    public static final String KIND_NEW = "new";

    public static final List<String> ALL_KINDS = List.of(KIND_SAME_GW, KIND_CROSS_GW, KIND_NEW);

    // ==================== outcome 标签值（7 值） ====================

    public static final String OUTCOME_SUCCEEDED = "succeeded";
    public static final String OUTCOME_REJECTED_EXPIRED = "rejected_expired";
    public static final String OUTCOME_REJECTED_MISMATCH = "rejected_mismatch";
    public static final String OUTCOME_REJECTED_OWNER_OTHER = "rejected_owner_other";
    public static final String OUTCOME_DEGRADED_TO_NEW = "degraded_to_new";
    public static final String OUTCOME_CANCELLED = "cancelled";
    public static final String OUTCOME_TIMEOUT = "timeout";

    public static final List<String> ALL_OUTCOMES = List.of(
            OUTCOME_SUCCEEDED, OUTCOME_REJECTED_EXPIRED, OUTCOME_REJECTED_MISMATCH,
            OUTCOME_REJECTED_OWNER_OTHER, OUTCOME_DEGRADED_TO_NEW,
            OUTCOME_CANCELLED, OUTCOME_TIMEOUT);

    // ==================== source 标签值（3 值） ====================

    public static final String SOURCE_BUFFER = "buffer";
    public static final String SOURCE_OFFLINE = "offline";
    public static final String SOURCE_MERGED = "merged";

    public static final List<String> ALL_SOURCES = List.of(SOURCE_BUFFER, SOURCE_OFFLINE, SOURCE_MERGED);

    // ==================== replay outcome 子集 ====================

    public static final String REPLAY_REPLAYED = "replayed";
    public static final String REPLAY_SKIPPED_ACKED = "skipped_acked";
    public static final String REPLAY_DROPPED = "dropped";

    public static final List<String> ALL_REPLAY_OUTCOMES = List.of(
            REPLAY_REPLAYED, REPLAY_SKIPPED_ACKED, REPLAY_DROPPED);

    // ==================== 实例字段 ====================

    private final MeterRegistry registry;
    private final GateConfig.ObservabilityConfig cfg;
    private final List<Duration> sloBuckets;

    /** Counter 缓存：key = name + "|" + tag1 + "|" + tag2... */
    private final ConcurrentMap<String, Counter> counterCache = new ConcurrentHashMap<>();
    /** Timer 缓存：key 规则同上 */
    private final ConcurrentMap<String, Timer> timerCache = new ConcurrentHashMap<>();

    public FlowMetrics(MeterRegistry registry, GateConfig.ObservabilityConfig cfg) {
        this.registry = registry;
        this.cfg = cfg == null ? new GateConfig.ObservabilityConfig() : cfg;
        this.sloBuckets = parseSlo(this.cfg.getHistogramSlo());
    }

    /** 便捷构造：直接从 {@link GateConfig} 提取 observability 段。 */
    public FlowMetrics(MeterRegistry registry, GateConfig gateConfig) {
        this(registry,
                gateConfig != null && gateConfig.getFlow() != null
                        ? gateConfig.getFlow().getObservability() : null);
    }

    /** Noop 实例：所有方法返回 noop meter（不写任何 registry）。 */
    public static FlowMetrics noop() {
        return new FlowMetrics(null, (GateConfig.ObservabilityConfig) null);
    }

    // ==================== 公共 API ====================

    /**
     * {@code gate_flow_takeover_total{result}} 计数器。
     *
     * @param result 6 值枚举之一（见 {@link #ALL_RESULTS}）
     */
    public Counter takeover(String result) {
        if (!isEnabled() || !cfg.isTakeoverTotalEnabled() || registry == null) {
            return NoopCounter.INSTANCE;
        }
        return counterCache.computeIfAbsent(
                cacheKey(M_TAKEOVER_TOTAL, result),
                k -> Counter.builder(M_TAKEOVER_TOTAL)
                        .description("Cross-instance takeover outcomes (gateway2-style result buckets)")
                        .tags(Tags.of(Tag.of(T_RESULT, result)))
                        .register(registry));
    }

    /**
     * {@code gate_flow_resume_total{kind,outcome}} 计数器。
     */
    public Counter resumeTotal(String kind, String outcome) {
        if (!isEnabled() || !cfg.isResumeTotalEnabled() || registry == null) {
            return NoopCounter.INSTANCE;
        }
        return counterCache.computeIfAbsent(
                cacheKey(M_RESUME_TOTAL, kind, outcome),
                k -> Counter.builder(M_RESUME_TOTAL)
                        .description("RESUME (and NEW) outcomes by kind")
                        .tags(Tags.of(Tag.of(T_KIND, kind), Tag.of(T_OUTCOME, outcome)))
                        .register(registry));
    }

    /**
     * {@code gate_flow_replay_total{source,outcome}} 计数器。
     */
    public Counter replay(String source, String outcome) {
        if (!isEnabled() || !cfg.isReplayTotalEnabled() || registry == null) {
            return NoopCounter.INSTANCE;
        }
        return counterCache.computeIfAbsent(
                cacheKey(M_REPLAY_TOTAL, source, outcome),
                k -> Counter.builder(M_REPLAY_TOTAL)
                        .description("Downstream frame replay outcomes by source")
                        .tags(Tags.of(Tag.of(T_SOURCE, source), Tag.of(T_OUTCOME, outcome)))
                        .register(registry));
    }

    /**
     * {@code gate_flow_latency_resume_ms{kind}} Timer，带 SLO bucket。
     */
    public Timer resumeLatency(String kind) {
        if (!isEnabled() || !cfg.isLatencyEnabled() || registry == null) {
            return NoopTimer.INSTANCE;
        }
        return timerCache.computeIfAbsent(
                cacheKey(M_LATENCY_RESUME, kind),
                k -> buildTimer(M_LATENCY_RESUME, Tags.of(Tag.of(T_KIND, kind)),
                        "RESUME (and NEW) end-to-end latency by kind (ms)"));
    }

    /**
     * {@code gate_flow_latency_cross_ready_ms} Timer，无 tag，仅跨实例 RESUME 路径。
     */
    public Timer crossReadyLatency() {
        if (!isEnabled() || !cfg.isLatencyEnabled() || registry == null) {
            return NoopTimer.INSTANCE;
        }
        return timerCache.computeIfAbsent(
                cacheKey(M_LATENCY_CROSS_READY, ""),
                k -> buildTimer(M_LATENCY_CROSS_READY, Tags.empty(),
                        "Cross-instance RESUME ready latency: AUTH -> first downstream frame writable (ms)"));
    }

    // ==================== 配置访问 ====================

    /** 是否启用 master switch + cross logger（用于 FlowSessionManager 判断是否写 gate.cross.event）。 */
    public boolean isCrossLoggerEnabled() {
        return isEnabled() && cfg.isCrossLoggerEnabled();
    }

    /** RESUME 超时阈值（ms）；{@link #isEnabled()} 为 false 时返回 Long.MAX_VALUE（即从不触发 timeout）。 */
    public long resumeTimeoutMs() {
        if (!isEnabled()) return Long.MAX_VALUE;
        long v = cfg.getResumeTimeoutMs();
        return v <= 0 ? Long.MAX_VALUE : v;
    }

    /** SLO bucket 列表（不可变）。 */
    public List<Duration> sloBuckets() {
        return sloBuckets;
    }

    /** 当前生效配置（便于启动日志 / debug）。 */
    public GateConfig.ObservabilityConfig config() {
        return cfg;
    }

    // ==================== 静态映射 ====================

    /**
     * {@link RedisFlowStore.CrossState} → result 标签值。
     *
     * <ul>
     *   <li>{@code RESUMED_OWNER_SAME / RESUMED_OWNER_CHANGED} → {@link #RESULT_SUCCEEDED}</li>
     *   <li>{@code EXPIRED} → {@link #RESULT_METADATA_MISSING}</li>
     *   <li>{@code REDIS_UNAVAILABLE} → {@link #RESULT_REDIS_UNAVAILABLE}</li>
     *   <li>{@code null} 或未来未知值 → {@link #RESULT_REJECTED}（fallback bucket）</li>
     * </ul>
     */
    public static String mapTakeoverResult(RedisFlowStore.CrossState state) {
        if (state == null) return RESULT_REJECTED;
        return switch (state) {
            case RESUMED_OWNER_SAME, RESUMED_OWNER_CHANGED -> RESULT_SUCCEEDED;
            case EXPIRED -> RESULT_METADATA_MISSING;
            case REDIS_UNAVAILABLE -> RESULT_REDIS_UNAVAILABLE;
        };
    }

    /**
     * {@link FlowResumeOutcome} → outcome 标签值。RESUMED / NEW 由调用方显式传 {@link #OUTCOME_SUCCEEDED}；
     * 此方法仅服务于拒绝 / 降级路径。
     */
    public static String mapResumeOutcome(FlowResumeOutcome reason) {
        if (reason == null) return OUTCOME_SUCCEEDED;
        return switch (reason) {
            case RESUMED -> OUTCOME_SUCCEEDED;
            case NEW -> OUTCOME_DEGRADED_TO_NEW;
            case REJECTED_EXPIRED -> OUTCOME_REJECTED_EXPIRED;
            case REJECTED_MISMATCH -> OUTCOME_REJECTED_MISMATCH;
            case REJECTED_OWNER_OTHER -> OUTCOME_REJECTED_OWNER_OTHER;
        };
    }

    // ==================== 内部实现 ====================

    private boolean isEnabled() {
        return cfg != null && cfg.isEnabled();
    }

    private Timer buildTimer(String name, Tags tags, String desc) {
        Timer.Builder b = Timer.builder(name)
                .description(desc)
                .tags(tags)
                .publishPercentileHistogram();
        if (!sloBuckets.isEmpty()) {
            b.serviceLevelObjectives(sloBuckets.toArray(new Duration[0]));
        }
        return b.register(registry);
    }

    private static String cacheKey(String name, String... tagValues) {
        StringBuilder sb = new StringBuilder(name.length() + 16);
        sb.append(name);
        for (String t : tagValues) {
            sb.append('|').append(t);
        }
        return sb.toString();
    }

    /**
     * 解析 SLO bucket 字符串（如 {@code "10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s"}）为 {@link Duration} 列表。
     * 非法 token 静默跳过 + warn 日志；返回不可变列表。
     */
    static List<Duration> parseSlo(String raw) {
        if (raw == null || raw.isBlank()) return Collections.emptyList();
        List<Duration> out = new ArrayList<>();
        for (String token : raw.split(",")) {
            String t = token.trim().toLowerCase();
            if (t.isEmpty()) continue;
            try {
                Duration d;
                if (t.endsWith("ms")) {
                    d = Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).trim()));
                } else if (t.endsWith("s")) {
                    d = Duration.ofMillis((long) (Double.parseDouble(t.substring(0, t.length() - 1).trim()) * 1000));
                } else {
                    d = Duration.ofMillis(Long.parseLong(t));
                }
                if (!d.isZero() && !d.isNegative()) out.add(d);
            } catch (NumberFormatException nfe) {
                logger.warn("Ignoring invalid SLO bucket '{}' in '{}'", t, raw);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ==================== Noop 实现（per-metric switch 关闭时返回） ====================

    /** 单例 noop Counter（永不写 registry）。 */
    static final class NoopCounter implements Counter {
        static final NoopCounter INSTANCE = new NoopCounter();
        private final Id id = new Id("noop", Tags.empty(), null, null, Type.COUNTER);
        @Override public void increment(double amount) { /* no-op */ }
        @Override public double count() { return 0d; }
        @Override public Id getId() { return id; }
    }

    /** 单例 noop Timer。 */
    static final class NoopTimer implements Timer {
        static final NoopTimer INSTANCE = new NoopTimer();
        private final Id id = new Id("noop", Tags.empty(), null, null, Type.TIMER);
        @Override public void record(long amount, java.util.concurrent.TimeUnit unit) { /* no-op */ }
        @Override public <T> T record(java.util.function.Supplier<T> f) { return f.get(); }
        @Override public <T> T recordCallable(java.util.concurrent.Callable<T> f) throws Exception { return f.call(); }
        @Override public void record(Runnable f) { f.run(); }
        @Override public long count() { return 0L; }
        @Override public double totalTime(java.util.concurrent.TimeUnit unit) { return 0d; }
        @Override public double max(java.util.concurrent.TimeUnit unit) { return 0d; }
        @Override public java.util.concurrent.TimeUnit baseTimeUnit() { return java.util.concurrent.TimeUnit.MILLISECONDS; }
        @Override public Id getId() { return id; }
        @Override public io.micrometer.core.instrument.distribution.HistogramSnapshot takeSnapshot() {
            return io.micrometer.core.instrument.distribution.HistogramSnapshot.empty(0L, 0d, 0d);
        }
    }

    /** 防止 unused import 警告（CompositeMeterRegistry 用作 javadoc 引用占位）。 */
    @SuppressWarnings("unused")
    private static final Class<?> KEEP_IMPORT_COMPOSITE = CompositeMeterRegistry.class;
}

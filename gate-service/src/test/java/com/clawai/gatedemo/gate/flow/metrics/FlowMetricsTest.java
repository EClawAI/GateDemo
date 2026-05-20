package com.clawai.gatedemo.gate.flow.metrics;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowResumeOutcome;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FlowMetrics}: mapping correctness, handle caching, noop safety,
 * SLO bucket parsing, per-metric switch behavior.
 */
class FlowMetricsTest {

    @Test
    void mapTakeoverResult_coversAllKnownStates() {
        assertEquals(FlowMetrics.RESULT_SUCCEEDED,
                FlowMetrics.mapTakeoverResult(RedisFlowStore.CrossState.RESUMED_OWNER_SAME));
        assertEquals(FlowMetrics.RESULT_SUCCEEDED,
                FlowMetrics.mapTakeoverResult(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED));
        assertEquals(FlowMetrics.RESULT_METADATA_MISSING,
                FlowMetrics.mapTakeoverResult(RedisFlowStore.CrossState.EXPIRED));
        assertEquals(FlowMetrics.RESULT_REDIS_UNAVAILABLE,
                FlowMetrics.mapTakeoverResult(RedisFlowStore.CrossState.REDIS_UNAVAILABLE));
        assertEquals(FlowMetrics.RESULT_REJECTED,
                FlowMetrics.mapTakeoverResult(null));
    }

    @Test
    void mapResumeOutcome_coversAllKnownOutcomes() {
        assertEquals(FlowMetrics.OUTCOME_SUCCEEDED,
                FlowMetrics.mapResumeOutcome(FlowResumeOutcome.RESUMED));
        assertEquals(FlowMetrics.OUTCOME_REJECTED_EXPIRED,
                FlowMetrics.mapResumeOutcome(FlowResumeOutcome.REJECTED_EXPIRED));
        assertEquals(FlowMetrics.OUTCOME_REJECTED_MISMATCH,
                FlowMetrics.mapResumeOutcome(FlowResumeOutcome.REJECTED_MISMATCH));
        assertEquals(FlowMetrics.OUTCOME_REJECTED_OWNER_OTHER,
                FlowMetrics.mapResumeOutcome(FlowResumeOutcome.REJECTED_OWNER_OTHER));
        assertEquals(FlowMetrics.OUTCOME_DEGRADED_TO_NEW,
                FlowMetrics.mapResumeOutcome(FlowResumeOutcome.NEW));
    }

    @Test
    void counterHandlesAreCachedPerTagCombination() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlowMetrics metrics = new FlowMetrics(registry, new GateConfig.ObservabilityConfig());

        Counter c1 = metrics.takeover(FlowMetrics.RESULT_SUCCEEDED);
        Counter c2 = metrics.takeover(FlowMetrics.RESULT_SUCCEEDED);
        assertSame(c1, c2, "Same tag combination must reuse the same Counter handle");

        Counter c3 = metrics.takeover(FlowMetrics.RESULT_METADATA_MISSING);
        assertNotSame(c1, c3, "Different tag combination must allocate a new Counter handle");

        // Both Counters are registered exactly once in the registry
        assertEquals(2L, registry.find(FlowMetrics.M_TAKEOVER_TOTAL).counters().size());
    }

    @Test
    void timerHandlesAreCachedAndConfiguredWithSloBuckets() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FlowMetrics metrics = new FlowMetrics(registry, new GateConfig.ObservabilityConfig());

        Timer t1 = metrics.resumeLatency(FlowMetrics.KIND_SAME_GW);
        Timer t2 = metrics.resumeLatency(FlowMetrics.KIND_SAME_GW);
        assertSame(t1, t2);

        Timer t3 = metrics.crossReadyLatency();
        Timer t4 = metrics.crossReadyLatency();
        assertSame(t3, t4);

        t1.record(50, TimeUnit.MILLISECONDS);
        assertEquals(1, registry.find(FlowMetrics.M_LATENCY_RESUME)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_SAME_GW)
                .timer().count());
    }

    @Test
    void perMetricSwitchDisabledReturnsNoop_andDoesNotRegister() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GateConfig.ObservabilityConfig cfg = new GateConfig.ObservabilityConfig();
        cfg.setTakeoverTotalEnabled(false);

        FlowMetrics metrics = new FlowMetrics(registry, cfg);
        Counter c = metrics.takeover(FlowMetrics.RESULT_SUCCEEDED);
        c.increment(); // 不应抛
        c.increment();

        assertEquals("noop", c.getId().getName());
        assertNull(registry.find(FlowMetrics.M_TAKEOVER_TOTAL).counter(),
                "noop counter must not register into the registry");
    }

    @Test
    void masterSwitchDisabledKillsAllMetrics() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GateConfig.ObservabilityConfig cfg = new GateConfig.ObservabilityConfig();
        cfg.setEnabled(false);

        FlowMetrics metrics = new FlowMetrics(registry, cfg);
        metrics.takeover(FlowMetrics.RESULT_SUCCEEDED).increment();
        metrics.resumeTotal(FlowMetrics.KIND_SAME_GW, FlowMetrics.OUTCOME_SUCCEEDED).increment();
        metrics.replay(FlowMetrics.SOURCE_BUFFER, FlowMetrics.REPLAY_REPLAYED).increment();
        metrics.resumeLatency(FlowMetrics.KIND_SAME_GW).record(10, TimeUnit.MILLISECONDS);
        metrics.crossReadyLatency().record(20, TimeUnit.MILLISECONDS);

        assertNull(registry.find(FlowMetrics.M_TAKEOVER_TOTAL).counter());
        assertNull(registry.find(FlowMetrics.M_RESUME_TOTAL).counter());
        assertNull(registry.find(FlowMetrics.M_REPLAY_TOTAL).counter());
        assertNull(registry.find(FlowMetrics.M_LATENCY_RESUME).timer());
        assertNull(registry.find(FlowMetrics.M_LATENCY_CROSS_READY).timer());
    }

    @Test
    void noopFactoryNeverThrowsAndRegistersNothing() {
        FlowMetrics noop = FlowMetrics.noop();
        noop.takeover(FlowMetrics.RESULT_SUCCEEDED).increment();
        noop.resumeTotal(FlowMetrics.KIND_NEW, FlowMetrics.OUTCOME_TIMEOUT).increment(100);
        noop.replay(FlowMetrics.SOURCE_MERGED, FlowMetrics.REPLAY_REPLAYED).increment(50);
        noop.resumeLatency(FlowMetrics.KIND_CROSS_GW).record(123, TimeUnit.MILLISECONDS);
        noop.crossReadyLatency().record(456, TimeUnit.MILLISECONDS);
        assertNotNull(noop.sloBuckets());
        assertTrue(noop.resumeTimeoutMs() > 0);
    }

    @Test
    void sloBucketsAreParsedFromConfig() {
        GateConfig.ObservabilityConfig cfg = new GateConfig.ObservabilityConfig();
        cfg.setHistogramSlo("5ms,50ms,500ms,2s");
        FlowMetrics metrics = new FlowMetrics(new SimpleMeterRegistry(), cfg);
        List<Duration> buckets = metrics.sloBuckets();
        assertEquals(4, buckets.size());
        assertEquals(Duration.ofMillis(5), buckets.get(0));
        assertEquals(Duration.ofMillis(50), buckets.get(1));
        assertEquals(Duration.ofMillis(500), buckets.get(2));
        assertEquals(Duration.ofMillis(2000), buckets.get(3));
    }

    @Test
    void invalidSloTokensAreSilentlyIgnored() {
        List<Duration> buckets = FlowMetrics.parseSlo("10ms,abc,500ms,xyz,1s");
        assertEquals(3, buckets.size());
        assertEquals(Duration.ofMillis(10), buckets.get(0));
        assertEquals(Duration.ofMillis(500), buckets.get(1));
        assertEquals(Duration.ofMillis(1000), buckets.get(2));
    }

    @Test
    void crossLoggerEnabledFollowsMasterAndCrossSwitch() {
        GateConfig.ObservabilityConfig cfg = new GateConfig.ObservabilityConfig();
        FlowMetrics m1 = new FlowMetrics(new SimpleMeterRegistry(), cfg);
        assertTrue(m1.isCrossLoggerEnabled(), "default true");

        cfg.setCrossLoggerEnabled(false);
        FlowMetrics m2 = new FlowMetrics(new SimpleMeterRegistry(), cfg);
        assertEquals(false, m2.isCrossLoggerEnabled());

        cfg.setCrossLoggerEnabled(true);
        cfg.setEnabled(false);
        FlowMetrics m3 = new FlowMetrics(new SimpleMeterRegistry(), cfg);
        assertEquals(false, m3.isCrossLoggerEnabled(), "master switch off should override sub-switch");
    }

    @Test
    void resumeTimeoutMsReturnsMaxValueWhenDisabled() {
        GateConfig.ObservabilityConfig cfg = new GateConfig.ObservabilityConfig();
        cfg.setEnabled(false);
        FlowMetrics metrics = new FlowMetrics(new SimpleMeterRegistry(), cfg);
        assertEquals(Long.MAX_VALUE, metrics.resumeTimeoutMs());
    }
}

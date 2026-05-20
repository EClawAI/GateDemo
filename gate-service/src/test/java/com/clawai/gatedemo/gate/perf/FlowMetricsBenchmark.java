package com.clawai.gatedemo.gate.perf;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowResumeOutcome;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * FlowMetrics 热点路径性能基线：覆盖 takeover / resume / replay / latency 4 类指标，
 * 单独度量 enabled 与 disabled（per-metric switch）两路径，避免开关误配造成隐式性能损耗。
 *
 * <p>设计要点：
 * <ul>
 *   <li>warmup/measurement 在 {@link BenchmarkRunner} 入口可调；</li>
 *   <li>Blackhole 消费 Counter 句柄，防止 JIT 死代码消除；</li>
 *   <li>State 复用 SimpleMeterRegistry，回避 PrometheusMeterRegistry 启动成本。</li>
 * </ul>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class FlowMetricsBenchmark {

    private MeterRegistry registry;
    private FlowMetrics enabled;
    private FlowMetrics disabledTakeover;

    @Setup
    public void setup() {
        this.registry = new SimpleMeterRegistry();

        GateConfig.ObservabilityConfig obs = new GateConfig.ObservabilityConfig();
        obs.setEnabled(true);
        this.enabled = new FlowMetrics(registry, obs);

        GateConfig.ObservabilityConfig obs2 = new GateConfig.ObservabilityConfig();
        obs2.setEnabled(true);
        obs2.setTakeoverTotalEnabled(false);
        this.disabledTakeover = new FlowMetrics(registry, obs2);
    }

    @Benchmark
    public void takeover_succeeded_enabled(Blackhole bh) {
        enabled.takeover(FlowMetrics.RESULT_SUCCEEDED).increment();
        bh.consume(enabled);
    }

    @Benchmark
    public void takeover_succeeded_disabled(Blackhole bh) {
        disabledTakeover.takeover(FlowMetrics.RESULT_SUCCEEDED).increment();
        bh.consume(disabledTakeover);
    }

    @Benchmark
    public void resume_sameGw_succeeded(Blackhole bh) {
        enabled.resumeTotal(FlowMetrics.KIND_SAME_GW, FlowMetrics.OUTCOME_SUCCEEDED).increment();
        bh.consume(enabled);
    }

    @Benchmark
    public void resume_crossGw_succeeded(Blackhole bh) {
        enabled.resumeTotal(FlowMetrics.KIND_CROSS_GW, FlowMetrics.OUTCOME_SUCCEEDED).increment();
        bh.consume(enabled);
    }

    @Benchmark
    public void replay_buffer_replayed(Blackhole bh) {
        enabled.replay(FlowMetrics.SOURCE_BUFFER, FlowMetrics.REPLAY_REPLAYED).increment();
        bh.consume(enabled);
    }

    @Benchmark
    public void latency_resume_sameGw(Blackhole bh) {
        enabled.resumeLatency(FlowMetrics.KIND_SAME_GW).record(250, TimeUnit.MICROSECONDS);
        bh.consume(enabled);
    }

    @Benchmark
    public void map_crossState(Blackhole bh) {
        bh.consume(FlowMetrics.mapTakeoverResult(RedisFlowStore.CrossState.RESUMED_OWNER_SAME));
    }

    @Benchmark
    public void map_resumeOutcome(Blackhole bh) {
        bh.consume(FlowMetrics.mapResumeOutcome(FlowResumeOutcome.RESUMED));
    }
}

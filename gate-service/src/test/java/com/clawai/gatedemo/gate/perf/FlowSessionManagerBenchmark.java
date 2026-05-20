package com.clawai.gatedemo.gate.perf;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowResumeOutcome;
import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.flow.FlowSessionManagerTestSupport;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link FlowSessionManager} 关键入口的 in-process 性能基线：
 * <ul>
 *   <li>{@code newFlow}：每次新 player 注册（不顶号）；</li>
 *   <li>{@code resume_same_gw}：先 newFlow 再 RESUME 同 flowId（命中本机 in-memory cache）；</li>
 *   <li>{@code resume_after_cross}：通过 stub 把 cross outcome 强制为
 *       {@link RedisFlowStore.CrossState#RESUMED_OWNER_CHANGED}，覆盖跨实例迁移热路径；</li>
 *   <li>{@code newFlow_topout}：同 player 重复 newFlow，命中顶号 + 旧 channel 关闭分支。</li>
 * </ul>
 *
 * <p>所有路径走 {@link FlowSessionManagerTestSupport#inMemoryStore}（同一份测试用 stub），
 * 跳过 Redis I/O；测得的 throughput 等价于 manager 自身锁 + state 维护的纯 CPU 开销。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class FlowSessionManagerBenchmark {

    private GateConfig gateConfig;
    private RedisFlowStore store;
    private FlowSessionManager manager;
    private final AtomicLong playerSeq = new AtomicLong(1_000_000L);

    @Setup(Level.Trial)
    public void setup() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-bench-01");
        GateConfig.FlowConfig flow = gateConfig.getFlow();
        flow.setDetachedTtlSeconds(60);
        flow.setMaxTtlSeconds(86_400);
        flow.setRenewalIntervalSeconds(15);
        flow.setDetachedScanIntervalSeconds(60);
        flow.setRedisKeyPrefix("gate:flow:");

        store = FlowSessionManagerTestSupport.inMemoryStore(flow);
        manager = new FlowSessionManager(gateConfig, store, new SimpleMeterRegistry());
        FlowSessionManagerTestSupport.init(manager);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        FlowSessionManagerTestSupport.shutdown(manager);
    }

    @Benchmark
    public void newFlow(Blackhole bh) {
        long pid = playerSeq.incrementAndGet();
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(pid, 1, ch);
        bh.consume(s);
    }

    @Benchmark
    public void newFlow_topout(Blackhole bh) {
        long pid = playerSeq.incrementAndGet();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        manager.newFlow(pid, 1, ch1);
        EmbeddedChannel ch2 = new EmbeddedChannel();
        FlowSession s = manager.newFlow(pid, 1, ch2);
        bh.consume(s);
    }

    @Benchmark
    public void resume_same_gw(Blackhole bh) {
        long pid = playerSeq.incrementAndGet();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession first = manager.newFlow(pid, 1, ch1);
        EmbeddedChannel ch2 = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume(
                first.getFlowId(), pid, 0L, ch2);
        bh.consume(result);
    }

    @Benchmark
    public void resume_cross_gw(Blackhole bh) {
        long pid = playerSeq.incrementAndGet();
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession first = manager.newFlow(pid, 1, ch1);
        // 把 store 临时切到 cross outcome；保持非全局副作用：每次 benchmark 都重新设置
        FlowSessionManagerTestSupport.setCrossOutcome(store,
                RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other");
        EmbeddedChannel ch2 = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume(
                first.getFlowId(), pid, 0L, ch2);
        bh.consume(result);
        if (result.outcome() != FlowResumeOutcome.RESUMED) {
            throw new IllegalStateException("cross resume expected RESUMED, got " + result.outcome());
        }
    }
}

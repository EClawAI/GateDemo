package com.clawai.gatedemo.gate.perf;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
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
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.TimeUnit;

/**
 * {@link MessageRouteRegistry} 查询热路径基线。
 *
 * <p>每帧入站 / 出站都会触发若干次 {@code getIdByName} / {@code getByMsgId} /
 * {@code getTargetService}，是网关热点之一。基线用于发现：
 * <ul>
 *   <li>ConcurrentHashMap 在 high QPS 下的 get 退化；</li>
 *   <li>未来重构（如改成 EnumMap / int 数组）能否带来明显收益。</li>
 * </ul>
 *
 * <p>本基准不依赖 {@code message_registry.json}：在 {@link #setup} 中手动 register
 * 三条典型路由，避免类加载顺序导致的 NPE。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RouteRegistryBenchmark {

    private int authMsgId;
    private int hbMsgId;
    private int ackMsgId;

    @Setup(Level.Trial)
    public void setup() {
        // 幂等：clear → register 三条固定路由，避免依赖 ClassLoader resource 顺序
        MessageRouteRegistry.clear();
        MessageRouteRegistry.register("AuthRequest", 1001, "gate");
        MessageRouteRegistry.register("ClientHeartbeat", 1002, "gate");
        MessageRouteRegistry.register("HeartbeatAck", 1003, null);
        this.authMsgId = 1001;
        this.hbMsgId = 1002;
        this.ackMsgId = 1003;
    }

    @Benchmark
    public void getIdByName_hit(Blackhole bh) {
        bh.consume(MessageRouteRegistry.getIdByName("ClientHeartbeat"));
    }

    @Benchmark
    public void getIdByName_miss(Blackhole bh) {
        bh.consume(MessageRouteRegistry.getIdByName("NotARealMessage"));
    }

    @Benchmark
    public void getByMsgId_hit(Blackhole bh) {
        bh.consume(MessageRouteRegistry.getByMsgId(hbMsgId));
    }

    @Benchmark
    public void getTargetService_hit(Blackhole bh) {
        bh.consume(MessageRouteRegistry.getTargetService(authMsgId));
    }

    @Benchmark
    public void getNameById_hit(Blackhole bh) {
        bh.consume(MessageRouteRegistry.getNameById(ackMsgId));
    }
}

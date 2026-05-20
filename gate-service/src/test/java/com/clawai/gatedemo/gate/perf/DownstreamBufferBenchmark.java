package com.clawai.gatedemo.gate.perf;

import com.clawai.gatedemo.gate.flow.DownstreamBuffer;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@link DownstreamBuffer} 热点路径基线：覆盖 enqueue / ackUpTo / drain 三类操作，
 * 横跨 {@code capacityEntries} 三档（64 / 256 / 1024）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>{@code enqueue_drop_oldest}：稳态 buffer 一直满，验证 drop_oldest 路径每帧开销；</li>
 *   <li>{@code ack_window}：周期性 ackUpTo 大窗口，验证 deque polleFirst 累积开销；</li>
 *   <li>{@code drain_window}：RESUME 路径核心，对 (fromSeq, latest] 区间做 list copy。</li>
 * </ul>
 *
 * <p>所有基准使用同一个 {@link WrappedMessage} 引用（buffer 内部不复制 body 字节），
 * 这与 production 路径一致。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class DownstreamBufferBenchmark {

    /** capacityEntries 档位；capacityBytes 设为 64MB 不构成限制。 */
    @Param({"64", "256", "1024"})
    public int capacity;

    private static final long CAPACITY_BYTES = 64L * 1024L * 1024L;

    private DownstreamBuffer buffer;
    private WrappedMessage sharedFrame;
    private int sharedFrameSize;
    private long seq;

    @Setup(Level.Iteration)
    public void setup() {
        this.buffer = new DownstreamBuffer(capacity, CAPACITY_BYTES,
                DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        // 256B 体 + 24B 头估值；与 server 侧 stamp 后的典型 HeartbeatAck/event 体积一档
        byte[] body = new byte[256];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i & 0x7f);
        MessageHeader header = new MessageHeader();
        header.setMessageId(99);
        header.setHasGwSeq(true);
        this.sharedFrame = new WrappedMessage(header, new RawMessageBody(body));
        this.sharedFrameSize = body.length + 24;
        this.seq = 0L;

        // 预填到满，让 enqueue benchmark 直接稳态命中 drop_oldest 分支
        long now = System.currentTimeMillis();
        for (int i = 0; i < capacity; i++) {
            buffer.enqueue(++seq, sharedFrame, sharedFrameSize, now);
        }
    }

    @Benchmark
    public void enqueue_drop_oldest(Blackhole bh) {
        DownstreamBuffer.EnqueueResult r = buffer.enqueue(++seq, sharedFrame, sharedFrameSize,
                System.currentTimeMillis());
        bh.consume(r);
    }

    /**
     * 模拟 ACK 推进：每次 ack 半个 buffer，再补到满，统计 ackUpTo 的均摊开销。
     */
    @Benchmark
    public void ack_half_then_refill(Blackhole bh) {
        long ackTo = buffer.maxGwSeq() - capacity / 2;
        int removed = buffer.ackUpTo(ackTo);
        bh.consume(removed);
        long now = System.currentTimeMillis();
        for (int i = 0; i < capacity / 2; i++) {
            buffer.enqueue(++seq, sharedFrame, sharedFrameSize, now);
        }
    }

    /**
     * RESUME 路径：drain (min, max] 全部 entries。
     */
    @Benchmark
    public void drain_all(Blackhole bh) {
        List<DownstreamBuffer.BufferedFrame> out = buffer.drain(buffer.minGwSeq() - 1);
        bh.consume(out);
    }

    /**
     * RESUME 路径：drain 后半段（典型 client lastClientRecvSeq ≈ buffer.maxGwSeq - 1/4 capacity）。
     */
    @Benchmark
    public void drain_quarter_tail(Blackhole bh) {
        long fromSeq = buffer.maxGwSeq() - capacity / 4;
        List<DownstreamBuffer.BufferedFrame> out = buffer.drain(fromSeq);
        bh.consume(out);
    }
}

package com.clawai.gatedemo.gate.perf;

import com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 编解码热点 benchmark：分别度量编码（业务对象 → 字节）与解码（字节 → 业务对象）。
 *
 * <p>用 {@link EmbeddedChannel} 走真实 Netty pipeline；
 * 用 {@link Param} 跨 32B / 256B / 2KB 三档 payload 度量压缩阈值（64B）两侧表现。
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class MessageCodecBenchmark {

    @Param({"32", "256", "2048"})
    public int payloadSize;

    private EmbeddedChannel encodeChannel;
    private EmbeddedChannel decodeChannel;
    private byte[] payload;
    private byte[] encodedBytes;

    @Setup
    public void setup() {
        this.payload = new byte[payloadSize];
        ThreadLocalRandom.current().nextBytes(payload);

        // 用一次 encoder 跑通拿到 wire bytes，给 decode benchmark 复用
        EmbeddedChannel oneShot = new EmbeddedChannel(new GameMessageEncoder());
        oneShot.writeOutbound(buildMessage(payload));
        ByteBuf out = oneShot.readOutbound();
        this.encodedBytes = new byte[out.readableBytes()];
        out.readBytes(this.encodedBytes);
        out.release();
        oneShot.finishAndReleaseAll();

        this.encodeChannel = new EmbeddedChannel(new GameMessageEncoder());
        this.decodeChannel = new EmbeddedChannel(new GameMessageDecoder());
    }

    @TearDown
    public void tearDown() {
        if (encodeChannel != null) encodeChannel.finishAndReleaseAll();
        if (decodeChannel != null) decodeChannel.finishAndReleaseAll();
    }

    @Benchmark
    public void encode(Blackhole bh) {
        encodeChannel.writeOutbound(buildMessage(payload));
        ByteBuf out = encodeChannel.readOutbound();
        bh.consume(out.readableBytes());
        out.release();
    }

    @Benchmark
    public void decode(Blackhole bh) {
        ByteBuf buf = Unpooled.wrappedBuffer(encodedBytes);
        decodeChannel.writeInbound(buf);
        WrappedMessage msg = decodeChannel.readInbound();
        bh.consume(msg);
    }

    private static WrappedMessage buildMessage(byte[] body) {
        MessageHeader header = new MessageHeader();
        header.setMessageId(0x1001);
        header.setRequestId(1);
        return new WrappedMessage(header, new RawMessageBody(body));
    }
}

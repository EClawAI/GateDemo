package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DownstreamBuffer} 单元测试：enqueue / ackUpTo / drain / overflow（两种策略）。
 *
 * <p>对应 {@code openspec/changes/add-flow-downstream-buffer/specs/gate-flow-downstream-buffer/spec.md}
 * 中「per-FlowSession DownstreamBuffer」与「ACK 触发裁剪」「overflow drop_oldest」「overflow force_detach」三组 scenario。
 */
class DownstreamBufferTest {

    private WrappedMessage frame(long gwSeq, byte[] body) {
        MessageHeader h = new MessageHeader();
        h.setHasGwSeq(true);
        h.setGwSeq(gwSeq);
        return new WrappedMessage(h, new RawMessageBody(body == null ? new byte[0] : body));
    }

    @Test
    void enqueue_thenAck_trimsOnlyAckedRange() {
        DownstreamBuffer buf = new DownstreamBuffer(100, 1 << 20, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        for (long i = 1; i <= 100; i++) {
            assertEquals(DownstreamBuffer.EnqueueResult.OK,
                    buf.enqueue(i, frame(i, new byte[8]), 24 + 8, System.currentTimeMillis()));
        }
        assertEquals(100, buf.size());
        int trimmed = buf.ackUpTo(70);
        assertEquals(70, trimmed);
        assertEquals(30, buf.size());
        assertEquals(71L, buf.minGwSeq());
        assertEquals(100L, buf.maxGwSeq());
    }

    @Test
    void drain_returnsOnlyEntriesAfterFromSeq() {
        DownstreamBuffer buf = new DownstreamBuffer(50, 1 << 20, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        for (long i = 1; i <= 10; i++) {
            buf.enqueue(i, frame(i, new byte[8]), 24 + 8, 0L);
        }
        List<DownstreamBuffer.BufferedFrame> after5 = buf.drain(5);
        assertEquals(5, after5.size());
        assertEquals(6L, after5.get(0).gwSeq());
        assertEquals(10L, after5.get(4).gwSeq());

        // drain 不裁剪
        assertEquals(10, buf.size());
    }

    @Test
    void overflowDropOldest_evictsOldestAndKeepsCapacity() {
        DownstreamBuffer buf = new DownstreamBuffer(3, 1 << 20, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        buf.enqueue(1, frame(1, new byte[8]), 32, 0L);
        buf.enqueue(2, frame(2, new byte[8]), 32, 0L);
        buf.enqueue(3, frame(3, new byte[8]), 32, 0L);
        DownstreamBuffer.EnqueueResult result = buf.enqueue(4, frame(4, new byte[8]), 32, 0L);
        assertEquals(DownstreamBuffer.EnqueueResult.DROPPED_OLDEST, result);
        assertEquals(3, buf.size());
        assertEquals(2L, buf.minGwSeq());
        assertEquals(4L, buf.maxGwSeq());
    }

    @Test
    void overflowForceDetach_refusesEnqueueOnFull() {
        DownstreamBuffer buf = new DownstreamBuffer(2, 1 << 20, DownstreamBuffer.OverflowPolicy.FORCE_DETACH);
        assertEquals(DownstreamBuffer.EnqueueResult.OK, buf.enqueue(1, frame(1, null), 16, 0L));
        assertEquals(DownstreamBuffer.EnqueueResult.OK, buf.enqueue(2, frame(2, null), 16, 0L));
        assertEquals(DownstreamBuffer.EnqueueResult.FORCE_DETACH,
                buf.enqueue(3, frame(3, null), 16, 0L));
        assertEquals(2, buf.size(), "force_detach 必须不入队");
    }

    @Test
    void byteCapacityTriggersEviction() {
        DownstreamBuffer buf = new DownstreamBuffer(1000, 100, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        buf.enqueue(1, frame(1, null), 60, 0L);
        buf.enqueue(2, frame(2, null), 60, 0L); // 60+60=120 > 100 → evict 1
        assertEquals(1, buf.size());
        assertEquals(2L, buf.minGwSeq());
    }

    @Test
    void drain_afterDrop_returnsRemainingOrderly() {
        DownstreamBuffer buf = new DownstreamBuffer(3, 1 << 20, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        for (long i = 1; i <= 5; i++) {
            buf.enqueue(i, frame(i, new byte[8]), 32, 0L);
        }
        // 期望 buffer 仅保留 3..5
        List<DownstreamBuffer.BufferedFrame> all = buf.drain(0);
        assertEquals(3, all.size());
        assertEquals(3L, all.get(0).gwSeq());
        assertEquals(5L, all.get(2).gwSeq());
        // 部分 drain
        List<DownstreamBuffer.BufferedFrame> after4 = buf.drain(4);
        assertEquals(1, after4.size());
        assertEquals(5L, after4.get(0).gwSeq());
    }

    @Test
    void totalBytesTracksEnqueueAndAck() {
        DownstreamBuffer buf = new DownstreamBuffer(100, 1 << 20, DownstreamBuffer.OverflowPolicy.DROP_OLDEST);
        for (long i = 1; i <= 5; i++) {
            buf.enqueue(i, frame(i, new byte[10]), 26, 0L);
        }
        assertEquals(5 * 26, buf.totalBytes());
        buf.ackUpTo(3);
        assertEquals(2 * 26, buf.totalBytes());
        assertTrue(buf.size() == 2);
    }
}

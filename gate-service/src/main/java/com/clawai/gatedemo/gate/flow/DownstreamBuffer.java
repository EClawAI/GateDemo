package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * per-FlowSession 的下行帧 ring buffer，保留 <b>已 stamp gwSeq 但尚未被客户端 ACK</b> 的帧。
 *
 * <p>设计要点（{@code openspec/changes/add-flow-downstream-buffer/design.md} §3）：
 * <ul>
 *   <li>非线程安全：所有访问由 {@link FlowSessionManager} 串行化（同 monitor）。</li>
 *   <li>容量双约束：{@code capacityEntries}（entries 数）与 {@code capacityBytes}（累计字节），任一触达即触发 overflow。</li>
 *   <li>两种 overflow：{@link OverflowPolicy#DROP_OLDEST}（默认）/ {@link OverflowPolicy#FORCE_DETACH}（由 Manager 检查返回值决定关 Channel）。</li>
 *   <li>{@link #drain(long)} 不移除 entries；ACK 才裁剪，避免重放后 ACK 未达又错失重放窗口。</li>
 * </ul>
 */
public final class DownstreamBuffer {

    /** Overflow 策略；具体动作由调用方（Manager）依据 {@link #enqueue} 返回值执行。 */
    public enum OverflowPolicy {
        DROP_OLDEST,
        FORCE_DETACH
    }

    /** 单条入队结果，调用方据此决定是否做 force_detach / 记录 drop。 */
    public enum EnqueueResult {
        /** 正常入队，无 overflow。 */
        OK,
        /** 触发 DROP_OLDEST：已踢掉最老 entry 并入队新 entry。 */
        DROPPED_OLDEST,
        /** 触发 FORCE_DETACH：未入队，调用方应关闭当前 Channel。 */
        FORCE_DETACH
    }

    /**
     * 缓冲条目：保留 {@link WrappedMessage} 引用以复用现有 encoder 路径；
     * 调用方 MUST NOT 在入队后再修改 {@link WrappedMessage#getBody()} 内容。
     */
    public record BufferedFrame(long gwSeq, WrappedMessage frame, int sizeBytes, long enqueuedAt) {}

    private final int capacityEntries;
    private final long capacityBytes;
    private final OverflowPolicy overflowPolicy;

    private final Deque<BufferedFrame> entries = new ArrayDeque<>();
    private long totalBytes = 0L;

    public DownstreamBuffer(int capacityEntries, long capacityBytes, OverflowPolicy overflowPolicy) {
        if (capacityEntries <= 0) throw new IllegalArgumentException("capacityEntries must be > 0");
        if (capacityBytes <= 0L) throw new IllegalArgumentException("capacityBytes must be > 0");
        this.capacityEntries = capacityEntries;
        this.capacityBytes = capacityBytes;
        this.overflowPolicy = overflowPolicy == null ? OverflowPolicy.DROP_OLDEST : overflowPolicy;
    }

    /**
     * 入队一条 entry；按 {@link OverflowPolicy} 决定 overflow 行为。
     *
     * @param gwSeq      调用方已经预先 stamp 的 gwSeq；应严格大于当前 {@link #maxGwSeq()}
     * @param frame      网关下行帧（已 stamp gwSeq、置 FLAG_HAS_GW_SEQ）
     * @param sizeBytes  编码后体积估值（用于容量计算；调用方可填 body.length + 24）
     * @param nowMillis  入队时间戳
     */
    public EnqueueResult enqueue(long gwSeq, WrappedMessage frame, int sizeBytes, long nowMillis) {
        boolean dropped = false;
        while (!entries.isEmpty()
                && (entries.size() >= capacityEntries || totalBytes + sizeBytes > capacityBytes)) {
            if (overflowPolicy == OverflowPolicy.FORCE_DETACH) {
                return EnqueueResult.FORCE_DETACH;
            }
            BufferedFrame oldest = entries.pollFirst();
            totalBytes -= oldest.sizeBytes;
            dropped = true;
        }
        // capacityBytes 可能小于单条 sizeBytes（病态配置）：drop_oldest 也救不了；为了对调用方简单，
        // 强行入队但记 DROPPED_OLDEST，便于运维通过 metric 发现配置不合理。
        if (totalBytes + sizeBytes > capacityBytes && entries.isEmpty()) {
            dropped = true;
        }
        entries.offerLast(new BufferedFrame(gwSeq, frame, sizeBytes, nowMillis));
        totalBytes += sizeBytes;
        return dropped ? EnqueueResult.DROPPED_OLDEST : EnqueueResult.OK;
    }

    /**
     * 丢弃所有 {@code gwSeq <= ackSeq} 的 entries。
     *
     * @return 被丢弃的数量
     */
    public int ackUpTo(long ackSeq) {
        int removed = 0;
        while (!entries.isEmpty() && entries.peekFirst().gwSeq <= ackSeq) {
            BufferedFrame f = entries.pollFirst();
            totalBytes -= f.sizeBytes;
            removed++;
        }
        return removed;
    }

    /**
     * 返回 ({@code fromSeq}, latest] 区间的所有 entries（按序），不移除任何 entry。
     */
    public List<BufferedFrame> drain(long fromSeq) {
        List<BufferedFrame> out = new ArrayList<>();
        for (BufferedFrame f : entries) {
            if (f.gwSeq > fromSeq) {
                out.add(f);
            }
        }
        return out;
    }

    public int size() { return entries.size(); }
    public long totalBytes() { return totalBytes; }
    public int capacityEntries() { return capacityEntries; }
    public long capacityBytes() { return capacityBytes; }
    public OverflowPolicy overflowPolicy() { return overflowPolicy; }

    public long minGwSeq() {
        return entries.isEmpty() ? 0L : entries.peekFirst().gwSeq;
    }

    public long maxGwSeq() {
        return entries.isEmpty() ? 0L : entries.peekLast().gwSeq;
    }

    public long oldestEnqueuedAt() {
        return entries.isEmpty() ? 0L : entries.peekFirst().enqueuedAt;
    }
}

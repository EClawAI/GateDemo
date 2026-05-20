package com.clawai.gatedemo.gate.flow;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 网关进程内的「逻辑层」会话对象（design.md §1 / §6）。
 *
 * <p>与 {@link io.netty.channel.Channel}（传输层）解耦：
 * <ul>
 *   <li>同一 FlowSession 在多次传输连接之间存活；</li>
 *   <li>Channel ↔ FlowSession 的当前绑定通过 {@link #FLOW_SESSION_KEY} 双向挂接；</li>
 *   <li>所有跨字段更新经由 {@link FlowSessionManager}，本类只承担状态容器职责。</li>
 * </ul>
 *
 * <p>本变更（Phase A）有意保持简单：状态仅 ATTACHED / DETACHED，DESTROYED 由
 * {@link FlowSessionManager} 从其内存索引移除来表达；下行 seq 与缓冲为 Phase B 范围。
 */
public final class FlowSession {

    /** 把 FlowSession 反挂到当前绑定的 Channel 上，便于 channelInactive 反查。 */
    public static final AttributeKey<FlowSession> FLOW_SESSION_KEY =
            AttributeKey.valueOf("flowSession");

    /** Phase A 仅 ATTACHED / DETACHED；DESTROYED 不显式持有（移出 Map 即销毁）。 */
    public enum State { ATTACHED, DETACHED }

    private final String flowId;
    private final long playerId;
    private final int gameId;
    private final String ownerGateId;
    private final long createdAt;

    private final AtomicReference<Channel> currentChannel = new AtomicReference<>();
    private volatile State state;
    private volatile long detachedAt;
    private volatile long expiresAt;
    private volatile long lastSeqAnchor;

    /** B1 引入：每个 FlowSession 维护一个下行单调序号（从 1 开始派号）。 */
    private final AtomicLong nextGwSeq = new AtomicLong(0L);
    /** B1 引入：下行缓冲；{@code null} 表示客户端不支持 gwSeq，禁用 buffer。 */
    private volatile DownstreamBuffer buffer;
    /** B1 引入：协商后的有效 features（{@link FlowFeatures}）。 */
    private volatile int features;

    public FlowSession(String flowId, long playerId, int gameId, String ownerGateId,
                       long createdAt, long expiresAt, Channel attached) {
        this.flowId = flowId;
        this.playerId = playerId;
        this.gameId = gameId;
        this.ownerGateId = ownerGateId;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.lastSeqAnchor = 0L;
        this.currentChannel.set(attached);
        this.state = (attached != null) ? State.ATTACHED : State.DETACHED;
        this.detachedAt = (attached != null) ? 0L : createdAt;
        if (attached != null) {
            attached.attr(FLOW_SESSION_KEY).set(this);
        }
    }

    public String getFlowId() { return flowId; }
    public long getPlayerId() { return playerId; }
    public int getGameId() { return gameId; }
    public String getOwnerGateId() { return ownerGateId; }
    public long getCreatedAt() { return createdAt; }

    public Channel getCurrentChannel() { return currentChannel.get(); }
    public State getState() { return state; }
    public long getDetachedAt() { return detachedAt; }
    public long getExpiresAt() { return expiresAt; }
    public long getLastSeqAnchor() { return lastSeqAnchor; }

    /**
     * 将新 Channel attach 到该 flow；若已存在旧 Channel 则 detach（不主动关闭，由 Manager 决定）。
     *
     * @return 旧 Channel（可能为 {@code null}）
     */
    Channel attach(Channel newChannel) {
        Channel prev = currentChannel.getAndSet(newChannel);
        if (prev != null) {
            prev.attr(FLOW_SESSION_KEY).set(null);
        }
        if (newChannel != null) {
            newChannel.attr(FLOW_SESSION_KEY).set(this);
        }
        this.state = State.ATTACHED;
        this.detachedAt = 0L;
        return prev;
    }

    /**
     * 仅在 {@code expected} 仍是当前 Channel 时置为 DETACHED；用于 channelInactive 防止误清新绑定。
     *
     * @return true=本次调用确实触发了 DETACH，false=已被新的 attach 覆盖
     */
    boolean detachIfMatches(Channel expected, long now) {
        if (currentChannel.compareAndSet(expected, null)) {
            this.state = State.DETACHED;
            this.detachedAt = now;
            if (expected != null) {
                expected.attr(FLOW_SESSION_KEY).set(null);
            }
            return true;
        }
        return false;
    }

    void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    void setLastSeqAnchor(long lastSeqAnchor) { this.lastSeqAnchor = lastSeqAnchor; }

    /** B1 引入：派发下一个 gwSeq；从 1 开始严格递增。 */
    public long incrementAndGetGwSeq() { return nextGwSeq.incrementAndGet(); }

    public long peekNextGwSeq() { return nextGwSeq.get(); }

    /**
     * B3 引入：跨实例 RESUME 时由 manager 用 {@code max(record.lastSeqAnchor, offline.maxGwSeq)}
     * 重置 nextGwSeq，避免新 push 与 offline 既有条目 gwSeq 冲突。
     * 仅在 {@code newValue > 当前值} 时生效。
     */
    void seedNextGwSeq(long newValue) {
        if (newValue <= 0) return;
        long cur;
        do {
            cur = nextGwSeq.get();
            if (newValue <= cur) return;
        } while (!nextGwSeq.compareAndSet(cur, newValue));
    }

    public DownstreamBuffer getBuffer() { return buffer; }

    /** B1：由 {@link FlowSessionManager} 装配 buffer；测试代码也可使用。 */
    public void setBuffer(DownstreamBuffer buffer) { this.buffer = buffer; }

    public int getFeatures() { return features; }

    /** B1：由 {@link FlowSessionManager} 写入协商后的 features；测试代码也可使用。 */
    public void setFeatures(int features) { this.features = features; }
}

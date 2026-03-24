package com.clawai.gatedemo.gate.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 简易熔断器：CLOSED → OPEN → HALF_OPEN → CLOSED
 */
public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    /** 熔断状态：关闭（正常）→ 打开（拒绝）→ 半开（试放行） */
    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long resetTimeoutMs;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    /** 最近一次失败时间，用于 OPEN 态超时后转入半开 */
    private volatile long lastFailureTime = 0;

    /**
     * @param failureThreshold 连续失败达到该次数后进入 OPEN
     * @param resetTimeoutMs   OPEN 后经过该毫秒可尝试半开
     */
    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    /**
     * CLOSED 放行；OPEN 且未过恢复期则拒绝，超时则 CAS 为 HALF_OPEN 并放行；HALF_OPEN 放行由调用方与 {@link #recordSuccess()} 配合闭合。
     *
     * @return 是否允许本次调用穿透到下游
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - lastFailureTime > resetTimeoutMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    logger.info("CircuitBreaker transitioned to HALF_OPEN");
                }
                return true;
            }
            return false;
        }
        // HALF_OPEN: allow one request through
        return true;
    }

    /** 清零失败计数；若当前为半开则切回关闭。 */
    public void recordSuccess() {
        failureCount.set(0);
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            logger.info("CircuitBreaker transitioned to CLOSED");
        }
    }

    /** 递增失败次数，达到阈值时从 CLOSED/HALF_OPEN 切入 OPEN 并刷新最后失败时间。 */
    public void recordFailure() {
        lastFailureTime = System.currentTimeMillis();
        int count = failureCount.incrementAndGet();
        if (count >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN) ||
                state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                logger.warn("CircuitBreaker transitioned to OPEN after {} failures", count);
            }
        }
    }

    /** @return 当前熔断状态快照 */
    public State getState() {
        return state.get();
    }
}

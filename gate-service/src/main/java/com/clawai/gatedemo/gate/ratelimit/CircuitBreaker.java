package com.clawai.gatedemo.gate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    private final int failureThreshold;
    private final long resetTimeoutMs;

    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    private volatile State state = State.CLOSED;

    public enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    public CircuitBreaker(int failureThreshold, long resetTimeoutMs) {
        this.failureThreshold = failureThreshold;
        this.resetTimeoutMs = resetTimeoutMs;
    }

    public boolean allowRequest() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - lastFailureTime.get() > resetTimeoutMs) {
                    state = State.HALF_OPEN;
                    logger.info("Circuit breaker half-open");
                    return true;
                }
                return false;
            case HALF_OPEN:
                return true;
            default:
                return false;
        }
    }

    public void recordSuccess() {
        if (state == State.HALF_OPEN) {
            successCount.incrementAndGet();
            if (successCount.get() >= 2) {
                state = State.CLOSED;
                failureCount.set(0);
                successCount.set(0);
                logger.info("Circuit breaker closed");
            }
        } else {
            failureCount.set(0);
        }
    }

    public void recordFailure() {
        lastFailureTime.set(System.currentTimeMillis());
        failureCount.incrementAndGet();

        if (state == State.HALF_OPEN) {
            state = State.OPEN;
            logger.info("Circuit breaker opened after half-open failure");
        } else if (failureCount.get() >= failureThreshold) {
            state = State.OPEN;
            logger.info("Circuit breaker opened after {} failures", failureThreshold);
        }
    }

    public State getState() {
        return state;
    }

    public void reset() {
        state = State.CLOSED;
        failureCount.set(0);
        successCount.set(0);
        lastFailureTime.set(0);
    }
}

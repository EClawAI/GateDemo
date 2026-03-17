package com.clawai.gatedemo.gate.resilience;

import org.junit.jupiter.api.Test;

import static com.clawai.gatedemo.gate.resilience.CircuitBreaker.State.*;
import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test
    void initialState_isClosed() {
        CircuitBreaker cb = new CircuitBreaker(3, 5000);
        assertEquals(CLOSED, cb.getState());
    }

    @Test
    void allowRequest_whenClosed_returnsTrue() {
        CircuitBreaker cb = new CircuitBreaker(3, 5000);
        assertTrue(cb.allowRequest());
    }

    @Test
    void closedToOpen_afterFailureThreshold() {
        CircuitBreaker cb = new CircuitBreaker(3, 5000);

        assertTrue(cb.allowRequest());
        cb.recordFailure();
        assertTrue(cb.allowRequest());
        cb.recordFailure();
        assertTrue(cb.allowRequest());
        cb.recordFailure();

        assertEquals(OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }

    @Test
    void openToHalfOpen_afterResetTimeout() throws InterruptedException {
        long resetTimeoutMs = 100;
        CircuitBreaker cb = new CircuitBreaker(2, resetTimeoutMs);

        cb.recordFailure();
        cb.recordFailure();
        assertEquals(OPEN, cb.getState());

        Thread.sleep(resetTimeoutMs + 50);

        assertTrue(cb.allowRequest());
        assertEquals(HALF_OPEN, cb.getState());
    }

    @Test
    void halfOpenToClosed_afterRecordSuccess() throws InterruptedException {
        long resetTimeoutMs = 100;
        CircuitBreaker cb = new CircuitBreaker(2, resetTimeoutMs);

        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(resetTimeoutMs + 50);
        cb.allowRequest();
        assertEquals(HALF_OPEN, cb.getState());

        cb.recordSuccess();
        assertEquals(CLOSED, cb.getState());
    }

    @Test
    void halfOpenToOpen_afterRecordFailure() throws InterruptedException {
        long resetTimeoutMs = 100;
        CircuitBreaker cb = new CircuitBreaker(2, resetTimeoutMs);

        cb.recordFailure();
        cb.recordFailure();
        Thread.sleep(resetTimeoutMs + 50);
        cb.allowRequest();
        assertEquals(HALF_OPEN, cb.getState());

        cb.recordFailure();
        assertEquals(OPEN, cb.getState());
    }

    @Test
    void recordSuccess_resetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(3, 5000);

        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();

        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CLOSED, cb.getState());
        cb.recordFailure();
        assertEquals(OPEN, cb.getState());
    }
}

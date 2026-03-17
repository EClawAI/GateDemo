package com.clawai.gatedemo.gate.resilience;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private static final String TEST_KEY = "player-1";
    private RateLimiter rateLimiter;

    @AfterEach
    void tearDown() {
        if (rateLimiter != null) {
            rateLimiter.shutdown();
        }
    }

    @Test
    void tryAcquire_withinLimit_returnsTrue() {
        rateLimiter = new RateLimiter(5, 10_000);

        for (int i = 0; i < 5; i++) {
            assertTrue(rateLimiter.tryAcquire(TEST_KEY),
                    "Request " + (i + 1) + " should be allowed");
        }
    }

    @Test
    void tryAcquire_overLimit_returnsFalse() {
        rateLimiter = new RateLimiter(3, 10_000);

        assertTrue(rateLimiter.tryAcquire(TEST_KEY));
        assertTrue(rateLimiter.tryAcquire(TEST_KEY));
        assertTrue(rateLimiter.tryAcquire(TEST_KEY));
        assertFalse(rateLimiter.tryAcquire(TEST_KEY));
    }

    @Test
    void tryAcquire_differentKeys_haveSeparateLimits() {
        rateLimiter = new RateLimiter(2, 10_000);

        assertTrue(rateLimiter.tryAcquire("key-a"));
        assertTrue(rateLimiter.tryAcquire("key-a"));
        assertFalse(rateLimiter.tryAcquire("key-a"));

        assertTrue(rateLimiter.tryAcquire("key-b"));
        assertTrue(rateLimiter.tryAcquire("key-b"));
        assertFalse(rateLimiter.tryAcquire("key-b"));
    }
}

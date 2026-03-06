package com.clawai.gatedemo.gate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    public boolean tryAcquire(String key) {
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow(windowMs));
        return window.tryAcquire(maxRequests);
    }

    public void reset(String key) {
        windows.remove(key);
    }

    public void shutdown() {
        windows.clear();
    }

    private static class SlidingWindow {
        private final long windowMs;
        private final AtomicInteger count = new AtomicInteger(0);
        private volatile long windowStart;

        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
        }

        synchronized boolean tryAcquire(int maxRequests) {
            long now = System.currentTimeMillis();

            if (now - windowStart >= windowMs) {
                windowStart = now;
                count.set(0);
            }

            if (count.get() < maxRequests) {
                count.incrementAndGet();
                return true;
            }

            return false;
        }
    }
}

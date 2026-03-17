package com.clawai.gatedemo.gate.resilience;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 滑动窗口限流器：支持 per-key（如 playerId）和全局限流
 */
public class RateLimiter {

    private final int maxRequests;
    private final long windowMs;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ratelimiter-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::cleanup, windowMs, windowMs, TimeUnit.MILLISECONDS);
    }

    public boolean tryAcquire(String key) {
        Window window = windows.computeIfAbsent(key, k -> new Window());
        long now = System.currentTimeMillis();

        if (now - window.windowStart > windowMs) {
            window.count.set(0);
            window.windowStart = now;
        }

        return window.count.incrementAndGet() <= maxRequests;
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().windowStart > windowMs * 2);
    }

    public void shutdown() {
        cleaner.shutdown();
    }

    private static class Window {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }
}

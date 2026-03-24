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
    /** 定期清理过期窗口，避免 map 无限增长 */
    private final ScheduledExecutorService cleaner;

    /**
     * @param maxRequests 每个时间窗口内每个 key 允许的最大请求数
     * @param windowMs    滑动窗口长度（毫秒），清理任务同周期运行
     */
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

    /**
     * 按 key 独立计数；窗口过期会重置计数。并发下可能略微超过上限。
     *
     * @param key 如 playerId 或全局常量
     * @return 未超窗口配额时为 true
     */
    public boolean tryAcquire(String key) {
        Window window = windows.computeIfAbsent(key, k -> new Window());
        long now = System.currentTimeMillis();

        if (now - window.windowStart > windowMs) {
            window.count.set(0);
            window.windowStart = now;
        }

        return window.count.incrementAndGet() <= maxRequests;
    }

    /** 移除过久未更新的窗口条目，降低内存占用。 */
    private void cleanup() {
        long now = System.currentTimeMillis();
        windows.entrySet().removeIf(e -> now - e.getValue().windowStart > windowMs * 2);
    }

    /** 停止后台清理线程，不再调度新任务。 */
    public void shutdown() {
        cleaner.shutdown();
    }

    private static class Window {
        volatile long windowStart = System.currentTimeMillis();
        final AtomicInteger count = new AtomicInteger(0);
    }
}

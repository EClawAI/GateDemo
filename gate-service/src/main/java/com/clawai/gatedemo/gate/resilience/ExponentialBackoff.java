package com.clawai.gatedemo.gate.resilience;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 指数退避策略
 * <p>
 * 用于重连等场景：失败时 delay 翻倍直至 maxDelay，成功时重置为 initialDelay。
 * 线程安全。
 */
public class ExponentialBackoff {

    private final long initialDelayMs;
    private final long maxDelayMs;
    private final double multiplier;

    private final AtomicLong currentDelayMs;

    /**
     * @param initialDelayMs 初始延迟（毫秒）
     * @param maxDelayMs     最大延迟（毫秒）
     * @param multiplier    乘数（失败时 currentDelay *= multiplier）
     */
    public ExponentialBackoff(long initialDelayMs, long maxDelayMs, double multiplier) {
        this.initialDelayMs = initialDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.multiplier = multiplier;
        this.currentDelayMs = new AtomicLong(initialDelayMs);
    }

    /**
     * 获取当前 delay 并推进到下一档（失败后调用）
     */
    public long getAndAdvance() {
        long current = currentDelayMs.get();
        long next = Math.min(maxDelayMs, (long) (current * multiplier));
        currentDelayMs.set(next);
        return current;
    }

    /**
     * 重置为初始 delay（成功后调用）
     */
    public void reset() {
        currentDelayMs.set(initialDelayMs);
    }

    /**
     * 获取当前 delay（不推进）
     */
    public long getCurrentDelayMs() {
        return currentDelayMs.get();
    }
}

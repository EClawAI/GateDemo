package com.clawai.gatedemo.client.reconnect;

/**
 * 可上限指数退避的重连策略：控制间隔倍增与最大重试次数，避免断线时对网关造成突发连接风暴。
 */
public class ExponentialBackoffPolicy implements ReconnectPolicy {

    private final long initialDelay;
    private final long maxDelay;
    private final double multiplier;
    private final int maxRetries;

    private int retryCount = 0;
    private long currentDelay;

    public ExponentialBackoffPolicy(long initialDelay, long maxDelay, double multiplier, int maxRetries) {
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
        this.multiplier = multiplier;
        this.maxRetries = maxRetries;
        this.currentDelay = initialDelay;
    }

    public static ExponentialBackoffPolicy defaultPolicy() {
        return new ExponentialBackoffPolicy(1000L, 30000L, 2.0, 10);
    }

    @Override
    public long getNextDelay() {
        return currentDelay;
    }

    @Override
    public void reset() {
        retryCount = 0;
        currentDelay = initialDelay;
    }

    @Override
    public boolean shouldRetry() {
        return retryCount < maxRetries;
    }

    @Override
    public int getRetryCount() {
        return retryCount;
    }

    public void onRetry() {
        retryCount++;
        currentDelay = (long) Math.min(currentDelay * multiplier, maxDelay);
    }
}

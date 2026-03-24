package com.clawai.gatedemo.client.reconnect;

/**
 * 重连策略契约：查询下次等待时长、是否仍可重试、计数重置及每次尝试后的状态推进，供 {@link ReconnectManager} 统一调度。
 */
public interface ReconnectPolicy {

    long getNextDelay();

    void reset();

    boolean shouldRetry();

    int getRetryCount();

    void onRetry();
}

package com.clawai.gatedemo.client.reconnect;

/**
 * 重连策略契约：查询下次等待时长、是否仍可重试、计数重置及每次尝试后的状态推进，供 {@link ReconnectManager} 统一调度。
 */
public interface ReconnectPolicy {

    /** @return 下一次重连前应休眠的毫秒数 */
    long getNextDelay();

    /** 连接成功后清零计数与间隔，供新一轮断线使用 */
    void reset();

    /** @return 是否尚未用尽 {@code maxRetries} 等上限 */
    boolean shouldRetry();

    /** @return 已记录的重试次数，供日志与回调展示 */
    int getRetryCount();

    /** 每次调度执行后由管理器调用，推进退避状态 */
    void onRetry();
}

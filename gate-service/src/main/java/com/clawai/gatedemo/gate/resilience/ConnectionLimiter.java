package com.clawai.gatedemo.gate.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket 连接数限制器
 * <p>
 * 追踪活跃连接数，超过上限时拒绝新连接。
 * 使用 tryAcquire()/release() 配对调用。
 */
@Component
public class ConnectionLimiter {

    private final int maxConnections;
    private final AtomicInteger activeCount = new AtomicInteger(0);

    public ConnectionLimiter(@Value("${gate.max-connections:10000}") int maxConnections) {
        this.maxConnections = maxConnections;
    }

    /**
     * 尝试获取一个连接槽位
     *
     * @return true 若成功获取，false 若已达上限
     */
    public boolean tryAcquire() {
        int current;
        int next;
        do {
            current = activeCount.get();
            if (current >= maxConnections) {
                return false;
            }
            next = current + 1;
        } while (!activeCount.compareAndSet(current, next));
        return true;
    }

    /**
     * 释放一个连接槽位（连接关闭时调用）
     */
    public void release() {
        activeCount.decrementAndGet();
    }

    /**
     * 当前活跃连接数
     */
    public int getActiveCount() {
        return activeCount.get();
    }

    /**
     * 最大允许连接数
     */
    public int getMaxConnections() {
        return maxConnections;
    }
}

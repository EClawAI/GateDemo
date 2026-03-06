package com.clawai.gatedemo.gate.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 限流器 - 使用滑动窗口算法实现请求限流
 *
 * 设计原理：
 * 滑动窗口是一种常用的限流算法，相比固定窗口更加平滑。
 * 它将时间轴分成多个小窗口，每个窗口记录请求数。
 *
 * 滑动窗口 vs 固定窗口：
 * | 特性 | 固定窗口 | 滑动窗口 |
 * |------|----------|----------|
 * | 实现复杂度 | 简单 | 较复杂 |
 * | 精度 | 粗略 | 精确 |
 * | 边界突变 | 有 | 无 |
 *
 * 示例（100请求/秒）：
 * 固定窗口：0-1秒内100个请求通过，1秒时刻突然100个请求也通过
 * 滑动窗口：更平滑，允许持续的高流量但拒绝突发
 *
 * 为什么需要限流？
 * - 防止DDoS攻击
 * - 保护后端服务不被压垮
 * - 保证服务质量
 *
 * 使用场景：
 * - 接口调用频率限制
 * - 每个玩家的请求频率限制
 * - 全局限流
 *
 * 实现细节：
 * - key: 限流对象（如玩家ID、IP地址）
 * - maxRequests: 时间窗口内最大请求数
 * - windowMs: 时间窗口大小（毫秒）
 */
public class RateLimiter {

    private static final Logger logger = LoggerFactory.getLogger(RateLimiter.class);

    /** 时间窗口内最大请求数 */
    private final int maxRequests;

    /** 时间窗口大小（毫秒） */
    private final long windowMs;

    /** 滑动窗口存储：key -> SlidingWindow */
    private final ConcurrentHashMap<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    /**
     * 构造函数
     * @param maxRequests 最大请求数
     * @param windowMs 时间窗口（毫秒）
     */
    public RateLimiter(int maxRequests, long windowMs) {
        this.maxRequests = maxRequests;
        this.windowMs = windowMs;
    }

    /**
     * 尝试获取一个请求配额
     *
     * 调用流程：
     * 1. 获取或创建该key的滑动窗口
     * 2. 在窗口中尝试获取配额
     *
     * @param key 限流对象（如玩家ID）
     * @return true表示允许请求，false表示被限流
     */
    public boolean tryAcquire(String key) {
        // 获取或创建滑动窗口
        SlidingWindow window = windows.computeIfAbsent(key, k -> new SlidingWindow(windowMs));
        // 尝试获取配额
        return window.tryAcquire(maxRequests);
    }

    /**
     * 重置限流状态
     *
     * 调用场景：玩家断开连接时
     *
     * @param key 限流对象
     */
    public void reset(String key) {
        windows.remove(key);
    }

    /**
     * 关闭限流器
     */
    public void shutdown() {
        windows.clear();
    }

    /**
     * 滑动窗口内部类
     *
     * 实现原理：
     * 1. 记录窗口开始时间
     * 2. 记录窗口内的请求数
     * 3. 每次请求检查当前时间是否超过窗口
     * 4. 如果超过，重置窗口；否则检查请求数
     */
    private static class SlidingWindow {
        /** 时间窗口大小 */
        private final long windowMs;
        
        /** 当前窗口内的请求数 */
        private final AtomicInteger count = new AtomicInteger(0);
        
        /** 窗口开始时间 */
        private volatile long windowStart;

        /**
         * 构造函数
         * @param windowMs 时间窗口（毫秒）
         */
        SlidingWindow(long windowMs) {
            this.windowMs = windowMs;
            this.windowStart = System.currentTimeMillis();
        }

        /**
         * 尝试获取配额
         *
         * 同步方法，保证线程安全
         *
         * @param maxRequests 最大请求数
         * @return true表示成功获取
         */
        synchronized boolean tryAcquire(int maxRequests) {
            long now = System.currentTimeMillis();

            // 检查是否需要重置窗口
            if (now - windowStart >= windowMs) {
                windowStart = now;
                count.set(0);
            }

            // 检查是否超过限制
            if (count.get() < maxRequests) {
                count.incrementAndGet();
                return true;
            }

            return false;
        }
    }
}

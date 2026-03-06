package com.clawai.gatedemo.gate.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 追踪ID生成器 - 为每个请求生成唯一追踪ID
 *
 * 设计原理：
 * 在分布式系统中，一个请求可能经过多个服务。
 * 为了方便追踪和排查问题，需要为每个请求生成一个唯一ID。
 * 这个ID会贯穿整个请求的生命周期，记录在日志中。
 *
 * 为什么需要TraceId？
 * - 排查问题：可以通过TraceId快速找到相关的所有日志
 * - 性能分析：追踪请求在各环节的耗时
 * - 链路追踪：为未来接入SkyWalking、Zipkin等做准备
 *
 * 实现机制：
 * - ThreadLocal：每个线程维护自己的TraceId
 * - 线程安全：使用ConcurrentHashMap存储活跃的Trace
 *
 * TraceId格式：
 * - 标准格式：32位UUID（无连字符）
 * - 消息格式：playerId-messageId-随机8位
 *
 * 使用方式：
 * 1. 请求进入时：generate() 生成新ID
 * 2. 处理过程中：getCurrent() 获取当前ID
 * 3. 线程结束时：clear() 清理
 * 4. 日志输出：在日志中包含TraceId
 *
 * 日志示例：
 * [abc123def456] IN  playerId=123  msgId=4097  seq=1
 * [abc123def456] OUT playerId=123  msgId=8193  seq=1  time=5ms
 *
 * @see MessageLogger 使用TraceId记录消息日志
 */
public class TraceIdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TraceIdGenerator.class);

    /** ThreadLocal：每个线程维护自己的TraceId */
    private static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();

    /** 活跃的Trace记录：用于监控和统计 */
    private static final ConcurrentHashMap<String, Long> activeTraces = new ConcurrentHashMap<>();

    /**
     * 生成新的TraceId
     *
     * 使用场景：
     * - 请求进入网关时
     * - 开始处理新的业务请求时
     *
     * @return 新生成的TraceId
     */
    public static String generate() {
        // 生成32位UUID（去掉连字符）
        String traceId = UUID.randomUUID().toString().replace("-", "");
        
        // 存入ThreadLocal，当前线程可以使用
        CURRENT_TRACE_ID.set(traceId);
        
        // 记录到活跃Trace表（用于监控）
        activeTraces.put(traceId, System.currentTimeMillis());
        
        return traceId;
    }

    /**
     * 获取当前线程的TraceId
     *
     * 如果当前线程没有TraceId，则自动生成一个
     *
     * @return 当前TraceId
     */
    public static String getCurrent() {
        String traceId = CURRENT_TRACE_ID.get();
        return traceId != null ? traceId : generate();
    }

    /**
     * 设置当前线程的TraceId
     *
     * 使用场景：
     * - 从上游服务接收已有TraceId
     * - 测试时手动设置
     *
     * @param traceId TraceId
     */
    public static void setCurrent(String traceId) {
        CURRENT_TRACE_ID.set(traceId);
    }

    /**
     * 清理当前线程的TraceId
     *
     * 重要：
     - 线程处理完请求后必须调用
     - 否则会造成ThreadLocal内存泄漏
     *
     * 建议使用try-finally：
     * <pre>
     * try {
     *     // 业务处理
     * } finally {
     *     TraceIdGenerator.clear();
     * }
     * </pre>
     */
    public static void clear() {
        CURRENT_TRACE_ID.remove();
    }

    /**
     * 为消息生成TraceId
     *
     * 格式：playerId-messageId-随机8位
     * 例如：12345-4097-a1b2c3d4
     *
     * 优点：更短，更易读
     * 缺点：理论上可能冲突
     *
     * @param playerId 玩家ID
     * @param messageId 消息ID
     * @return TraceId
     */
    public static String generateForMessage(long playerId, short messageId) {
        return String.format("%d-%d-%s", playerId, messageId, UUID.randomUUID().toString().substring(0, 8));
    }
}

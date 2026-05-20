package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * B2：跨实例 owner 迁移成功后，向 Redis Pub/Sub 通道发布 evict 事件，请求旧 owner 关闭本地 Channel。
 *
 * <p>通道名 / 是否启用 由 {@link GateConfig.CrossConfig} 控制；当
 * {@code gate.flow.cross.enabled=false} 时，{@link #publish} 直接 no-op，但仍记 metric 以观测「降级路径生效」。
 *
 * <p>{@link StringRedisTemplate} 可能为 {@code null}（仅在单测环境）—— 通过 {@link Autowired#required()}=false 注入并在使用前判空。
 */
@Component
public class FlowEvictPublisher {

    private static final Logger logger = LoggerFactory.getLogger(FlowEvictPublisher.class);
    /** 跨实例独立 logger（add-flow-observability-buckets）。 */
    private static final Logger crossEventLogger = LoggerFactory.getLogger("gate.cross.event");

    private final StringRedisTemplate redis;
    private final GateConfig gateConfig;
    private final MeterRegistry meterRegistry;
    private final FlowMetrics flowMetrics;

    public FlowEvictPublisher(@Autowired(required = false) StringRedisTemplate redis,
                              GateConfig gateConfig,
                              @Autowired(required = false) MeterRegistry meterRegistry) {
        this.redis = redis;
        this.gateConfig = gateConfig;
        this.meterRegistry = meterRegistry;
        this.flowMetrics = meterRegistry != null
                ? new FlowMetrics(meterRegistry, gateConfig)
                : FlowMetrics.noop();
    }

    /**
     * 发布一条 evict 事件。
     *
     * @param flowId            刚被迁移的 flowId
     * @param newOwnerGateId    新 owner（通常是本实例 {@code gateConfig.getId()}）
     * @param previousOwnerGateId  旧 owner（来自 crossTakeover 返回），调试 / 日志用
     */
    public void publish(String flowId, String newOwnerGateId, String previousOwnerGateId) {
        GateConfig.CrossConfig cross = gateConfig.getFlow().getCross();
        if (cross == null || !cross.isEnabled()) {
            return;
        }
        if (!cross.isPublishSelfEvict() && newOwnerGateId.equals(previousOwnerGateId)) {
            // 没必要让自己驱逐自己；与 listener 的 self-skip 双重防御。
            return;
        }
        if (redis == null) {
            logger.warn("FlowEvictPublisher: StringRedisTemplate not available, skipping publish flowId={}", flowId);
            return;
        }

        long now = System.currentTimeMillis();
        String payload = String.format(
                "{\"flowId\":\"%s\",\"newOwnerGateId\":\"%s\",\"evictedAt\":%d}",
                escape(flowId), escape(newOwnerGateId), now);
        try {
            redis.convertAndSend(cross.getEvictChannel(), payload);
            if (meterRegistry != null) {
                Counter.builder("gate_flow_cross_evict_published_total").register(meterRegistry).increment();
            }
            // 新观测性：发起方视角 result=evicted_remote
            flowMetrics.takeover(FlowMetrics.RESULT_EVICTED_REMOTE).increment();
            logger.info("event=flow.evict.published flowId={} newOwnerGateId={} previousOwnerGateId={}",
                    flowId, newOwnerGateId, previousOwnerGateId);
            // 跨实例独立 logger
            if (flowMetrics.isCrossLoggerEnabled()) {
                crossEventLogger.info(
                        "event=cross.takeover.publish flowId={} previousOwnerGateId={} newOwnerGateId={}",
                        flowId, previousOwnerGateId, newOwnerGateId);
            }
        } catch (Exception e) {
            logger.warn("Publish evict failed flowId={} channel={}: {}",
                    flowId, cross.getEvictChannel(), e.getMessage());
        }
    }

    /** 最小化 JSON 字符串转义，仅替换双引号 / 反斜杠 / 控制字符，flowId/gateId 不应包含其他。 */
    private static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

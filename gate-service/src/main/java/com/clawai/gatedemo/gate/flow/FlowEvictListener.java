package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * B2：订阅 {@code gate:flow:evict} 通道，把跨实例 owner 迁移事件转化为本地驱逐。
 *
 * <p>解析策略：手写正则取 {@code flowId} 与 {@code newOwnerGateId}，避免引入额外 JSON 库；
 * 字段缺失 / 解析失败 → 跳过并计数 {@code gate_flow_cross_evict_invalid_total}。
 *
 * <p>self-skip：若 {@code newOwnerGateId == self.gateId} → 不调用 manager，避免误删自己刚装好的 flow。
 */
@Component
public class FlowEvictListener implements MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(FlowEvictListener.class);
    /** 跨实例独立 logger（add-flow-observability-buckets）。 */
    private static final Logger crossEventLogger = LoggerFactory.getLogger("gate.cross.event");

    private static final Pattern FLOW_ID = Pattern.compile("\"flowId\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern NEW_OWNER = Pattern.compile("\"newOwnerGateId\"\\s*:\\s*\"([^\"]+)\"");

    private final FlowSessionManager manager;
    private final GateConfig gateConfig;
    private final MeterRegistry meterRegistry;
    private final FlowMetrics flowMetrics;

    public FlowEvictListener(FlowSessionManager manager,
                             GateConfig gateConfig,
                             @Autowired(required = false) MeterRegistry meterRegistry) {
        this.manager = manager;
        this.gateConfig = gateConfig;
        this.meterRegistry = meterRegistry;
        this.flowMetrics = meterRegistry != null
                ? new FlowMetrics(meterRegistry, gateConfig)
                : FlowMetrics.noop();
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        Matcher flowM = FLOW_ID.matcher(body);
        Matcher ownerM = NEW_OWNER.matcher(body);
        if (!flowM.find() || !ownerM.find()) {
            increment("gate_flow_cross_evict_invalid_total", Tags.empty());
            logger.warn("Invalid flow evict payload: {}", body);
            return;
        }
        String flowId = flowM.group(1);
        String newOwnerGateId = ownerM.group(1);

        if (newOwnerGateId.equals(gateConfig.getId())) {
            increment("gate_flow_cross_evict_received_total", Tags.of(Tag.of("ignored", "self")));
            return;
        }

        boolean removed = manager.evictByCrossInstanceTakeover(flowId, newOwnerGateId);
        Tags tags = removed
                ? Tags.empty()
                : Tags.of(Tag.of("ignored", "unknown"));
        increment("gate_flow_cross_evict_received_total", tags);
        // 新观测性：被驱逐方视角 result=evicted_by_remote（仅在确实驱逐本地 flow 时计）
        if (removed) {
            flowMetrics.takeover(FlowMetrics.RESULT_EVICTED_BY_REMOTE).increment();
        }
        logger.info("event=flow.evict.received flowId={} from={} to={} removed={}",
                flowId, gateConfig.getId(), newOwnerGateId, removed);
        // 跨实例独立 logger
        if (flowMetrics.isCrossLoggerEnabled()) {
            crossEventLogger.info(
                    "event=cross.evict.received flowId={} newOwnerGateId={} localOwner={} removed={}",
                    flowId, newOwnerGateId, gateConfig.getId(), removed);
        }
    }

    private void increment(String name, Tags tags) {
        if (meterRegistry == null) return;
        Counter.builder(name).tags(tags).register(meterRegistry).increment();
    }
}

package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FlowEvictPublisher} 的单元测试，重点覆盖 add-flow-observability-buckets
 * 引入的 {@code result=evicted_remote} 计数 + cross-event logger 控制位。
 */
class FlowEvictPublisherTest {

    private GateConfig gateConfig;
    private SimpleMeterRegistry meterRegistry;
    private RecordingRedisTemplate redis;
    private FlowEvictPublisher publisher;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-self-01");
        gateConfig.getFlow().getCross().setEnabled(true);

        meterRegistry = new SimpleMeterRegistry();
        redis = new RecordingRedisTemplate();
        publisher = new FlowEvictPublisher(redis, gateConfig, meterRegistry);
    }

    @Test
    void publish_emitsResultEvictedRemote() {
        publisher.publish("flow-pub-a", "gate-self-01", "gate-other-77");

        assertEquals(1, redis.published.size(), "Redis convertAndSend 应被调用一次");
        assertNotNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter());
        assertEquals(1.0, meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter().count());
    }

    @Test
    void publish_selfEvict_skipsAndDoesNotEmit() {
        publisher.publish("flow-self", "gate-self-01", "gate-self-01");

        assertEquals(0, redis.published.size(), "self-evict 默认不发布");
        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter(),
                "self-evict 跳过路径不应 emit evicted_remote");
    }

    @Test
    void publish_crossDisabled_noOpAndNoMetric() {
        gateConfig.getFlow().getCross().setEnabled(false);
        publisher.publish("flow-disabled", "gate-self-01", "gate-other-77");

        assertEquals(0, redis.published.size());
        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter());
    }

    @Test
    void publish_noRedis_noOpButNoNpe() {
        FlowEvictPublisher noRedis = new FlowEvictPublisher(null, gateConfig, meterRegistry);
        // 不应抛出
        noRedis.publish("flow-nr", "gate-self-01", "gate-other-77");
        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter(),
                "无 redis 时 publish 提前返回，不应 emit");
    }

    @Test
    void publish_observabilityDisabled_doesNotEmitNewMetric() {
        gateConfig.getFlow().getObservability().setEnabled(false);
        FlowEvictPublisher p = new FlowEvictPublisher(redis, gateConfig, meterRegistry);
        p.publish("flow-off", "gate-self-01", "gate-other-77");

        assertEquals(1, redis.published.size(), "redis 发布行为不受 observability 开关影响");
        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_REMOTE).counter(),
                "observability master=false 时新 metric 不应注册");
        // 旧 metric 仍工作
        assertTrue(meterRegistry.find("gate_flow_cross_evict_published_total")
                .counter().count() >= 1);
    }

    /** Minimal stub: 捕获 convertAndSend 调用，不做实际网络。 */
    static class RecordingRedisTemplate extends StringRedisTemplate {
        final List<String> published = new ArrayList<>();

        @Override
        public Long convertAndSend(String channel, Object message) {
            published.add(channel + "|" + message);
            return 1L;
        }
    }
}

package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.Message;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B2：{@link FlowEvictListener} 的单元测试。
 *
 * <p>覆盖：
 * <ul>
 *   <li>合法 JSON → 调用 manager.evictByCrossInstanceTakeover；</li>
 *   <li>self-skip：newOwnerGateId == self → 不调用；</li>
 *   <li>JSON 字段缺失 / 非法 → 不调用 + 计数 invalid；</li>
 *   <li>未知 flowId → manager 返回 false（不抛）。</li>
 * </ul>
 *
 * <p>不依赖真实 Redis：直接构造 {@link FakeMessage} 喂给 onMessage。
 */
class FlowEvictListenerTest {

    private GateConfig gateConfig;
    private RecordingManager managerSpy;
    private FlowEvictListener listener;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-self-01");
        gateConfig.getFlow().setRedisKeyPrefix("gate:flow:");

        managerSpy = new RecordingManager(gateConfig);
        meterRegistry = new SimpleMeterRegistry();
        listener = new FlowEvictListener(managerSpy, gateConfig, meterRegistry);
    }

    @Test
    void onMessage_validJson_callsEvictByCrossInstanceTakeover() {
        String body = "{\"flowId\":\"flow-abc\",\"newOwnerGateId\":\"gate-other-77\",\"evictedAt\":1700000000000}";
        listener.onMessage(new FakeMessage(body), null);

        assertEquals("flow-abc", managerSpy.lastFlowId);
        assertEquals("gate-other-77", managerSpy.lastNewOwner);
        assertEquals(1, managerSpy.calls);
    }

    @Test
    void onMessage_selfNewOwner_skipsManager() {
        String body = "{\"flowId\":\"flow-self\",\"newOwnerGateId\":\"gate-self-01\",\"evictedAt\":0}";
        listener.onMessage(new FakeMessage(body), null);

        assertEquals(0, managerSpy.calls, "self-skip：不能驱逐自己刚装好的 flow");
        assertNull(managerSpy.lastFlowId);
    }

    @Test
    void onMessage_missingFlowIdField_invalidPayload_skipsManager() {
        String body = "{\"newOwnerGateId\":\"gate-other\"}";
        listener.onMessage(new FakeMessage(body), null);
        assertEquals(0, managerSpy.calls);
    }

    @Test
    void onMessage_missingOwnerField_invalidPayload_skipsManager() {
        String body = "{\"flowId\":\"flow-abc\"}";
        listener.onMessage(new FakeMessage(body), null);
        assertEquals(0, managerSpy.calls);
    }

    @Test
    void onMessage_garbagePayload_doesNotThrow() {
        String body = "not a json {{{}}";
        listener.onMessage(new FakeMessage(body), null);
        assertEquals(0, managerSpy.calls);
    }

    @Test
    void onMessage_unknownFlow_managerReturnsFalse_listenerStillCompletes() {
        managerSpy.returnRemoved = false;
        String body = "{\"flowId\":\"unknown-flow\",\"newOwnerGateId\":\"gate-other-77\"}";
        listener.onMessage(new FakeMessage(body), null);
        assertEquals(1, managerSpy.calls, "未知 flow 仍要调用 manager，由 manager 返回 false");
        assertFalse(managerSpy.lastResult);
    }

    @Test
    void onMessage_jsonWithSpaces_stillParses() {
        String body = "{ \"flowId\" : \"flow-x\" , \"newOwnerGateId\" : \"gate-other-77\" }";
        listener.onMessage(new FakeMessage(body), null);
        assertTrue(managerSpy.calls > 0);
        assertEquals("flow-x", managerSpy.lastFlowId);
    }

    // ==================== add-flow-observability-buckets ====================

    @Test
    void onMessage_validJson_emitsResultEvictedByRemote() {
        managerSpy.returnRemoved = true;
        String body = "{\"flowId\":\"flow-obs-a\",\"newOwnerGateId\":\"gate-other-77\",\"evictedAt\":0}";
        listener.onMessage(new FakeMessage(body), null);

        assertNotNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_BY_REMOTE).counter(),
                "evicted_by_remote 必须在 manager 实际驱逐时计数");
        assertEquals(1.0, meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_BY_REMOTE).counter().count());
    }

    @Test
    void onMessage_unknownFlow_doesNotEmitEvictedByRemote() {
        managerSpy.returnRemoved = false;
        String body = "{\"flowId\":\"unknown\",\"newOwnerGateId\":\"gate-other-77\"}";
        listener.onMessage(new FakeMessage(body), null);

        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_BY_REMOTE).counter(),
                "manager 未实际驱逐时不应 emit evicted_by_remote");
    }

    @Test
    void onMessage_selfNewOwner_doesNotEmitEvictedByRemote() {
        String body = "{\"flowId\":\"flow-self\",\"newOwnerGateId\":\"gate-self-01\"}";
        listener.onMessage(new FakeMessage(body), null);

        assertNull(meterRegistry.find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_EVICTED_BY_REMOTE).counter(),
                "self-skip 时不应 emit evicted_by_remote");
    }

    /** 最小 Message 实现，避免依赖 Spring redis 包内部实现。 */
    static class FakeMessage implements Message {
        private final byte[] body;
        FakeMessage(String s) { this.body = s.getBytes(StandardCharsets.UTF_8); }
        @Override public byte[] getBody() { return body; }
        @Override public byte[] getChannel() { return "gate:flow:evict".getBytes(StandardCharsets.UTF_8); }
    }

    /** 不启动 scheduler，仅捕获 evictByCrossInstanceTakeover 调用。 */
    static class RecordingManager extends FlowSessionManager {
        volatile String lastFlowId;
        volatile String lastNewOwner;
        volatile boolean lastResult;
        volatile int calls;
        volatile boolean returnRemoved = true;

        RecordingManager(GateConfig gateConfig) {
            super(gateConfig,
                    new FlowSessionManagerTest.InMemoryRedisFlowStoreStub(gateConfig.getFlow()),
                    new SimpleMeterRegistry());
        }

        @Override
        public boolean evictByCrossInstanceTakeover(String flowId, String newOwnerGateId) {
            calls++;
            lastFlowId = flowId;
            lastNewOwner = newOwnerGateId;
            lastResult = returnRemoved;
            return returnRemoved;
        }
    }
}

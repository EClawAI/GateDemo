package com.clawai.gatedemo.gate.lifecycle;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.flow.FlowSessionManagerTestSupport;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import com.clawai.gatedemo.gate.service.OfflineMessageService;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.gate.transport.GatewayTransport;
import com.clawai.gatedemo.gate.transport.GatewayTransportRegistry;
import com.clawai.gatedemo.gate.transport.NoopGatewayTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单元测试：{@link GracefulShutdownManager} 多 transport 编排。
 * 对应 spec「GracefulShutdownManager 遍历所有 transport」。
 */
class GracefulShutdownManagerTest {

    private FlowSessionManager flowManager;
    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        GateConfig cfg = new GateConfig();
        cfg.setId("gate-test");
        cfg.getFlow().setDetachedTtlSeconds(10);
        cfg.getFlow().setMaxTtlSeconds(60);
        cfg.getFlow().setRenewalIntervalSeconds(5);
        cfg.getFlow().setDetachedScanIntervalSeconds(60);

        RedisFlowStore stub = FlowSessionManagerTestSupport.inMemoryStore(cfg.getFlow());
        flowManager = new FlowSessionManager(cfg, stub, new SimpleMeterRegistry());
        FlowSessionManagerTestSupport.init(flowManager);

        playerService = new PlayerService((OfflineMessageService) null, flowManager);
    }

    @AfterEach
    void tearDown() {
        FlowSessionManagerTestSupport.shutdown(flowManager);
    }

    @Test
    void shutdown_invokesStopAcceptingThenStop_forEveryActiveTransport() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket");
        NoopGatewayTransport tcp = new NoopGatewayTransport("tcp");
        NoopGatewayTransport disabled = new NoopGatewayTransport("kcp", false);
        GatewayTransportRegistry registry = new GatewayTransportRegistry(
                new ListProvider<>(List.of(ws, tcp, disabled)));

        GracefulShutdownManager mgr = new GracefulShutdownManager(registry, playerService);
        ReflectionTestUtils.setField(mgr, "enabled", true);
        ReflectionTestUtils.setField(mgr, "drainTimeoutSeconds", 1);

        mgr.onShutdown();

        // disabled transport 不应被触发
        assertTrue(disabled.events().isEmpty(),
                "disabled transport should not receive any call, got=" + disabled.events());

        // ws + tcp 都收到 stopAccepting + stop（顺序：先所有 stopAccepting，再所有 stop）
        assertEquals(List.of("websocket:stopAccepting", "websocket:stop"), ws.events());
        assertEquals(List.of("tcp:stopAccepting", "tcp:stop"), tcp.events());
    }

    @Test
    void shutdown_singleTransportThrows_doesNotBlockOthers() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket", true, true);
        NoopGatewayTransport tcp = new NoopGatewayTransport("tcp");
        GatewayTransportRegistry registry = new GatewayTransportRegistry(
                new ListProvider<>(List.of(ws, tcp)));

        GracefulShutdownManager mgr = new GracefulShutdownManager(registry, playerService);
        ReflectionTestUtils.setField(mgr, "enabled", true);
        ReflectionTestUtils.setField(mgr, "drainTimeoutSeconds", 1);

        mgr.onShutdown(); // 应当吞 ws 的异常，继续走 tcp

        assertTrue(tcp.events().contains("tcp:stopAccepting"));
        assertTrue(tcp.events().contains("tcp:stop"));
        // ws stopAccepting 被触发（虽然抛错），stop 仍会在第二阶段被调用
        assertTrue(ws.events().contains("websocket:stopAccepting"));
        assertTrue(ws.events().contains("websocket:stop"));
    }

    @Test
    void shutdown_disabled_skipsEverything() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket");
        GatewayTransportRegistry registry = new GatewayTransportRegistry(
                new ListProvider<>(List.of(ws)));

        GracefulShutdownManager mgr = new GracefulShutdownManager(registry, playerService);
        ReflectionTestUtils.setField(mgr, "enabled", false);
        mgr.onShutdown();

        assertTrue(ws.events().isEmpty());
    }

    /** 最小化 {@link ObjectProvider} 测试桩。 */
    private static class ListProvider<T> implements ObjectProvider<T> {
        private final List<T> items;
        ListProvider(List<T> items) { this.items = new ArrayList<>(items); }
        @Override public Stream<T> stream() { return items.stream(); }
        @Override public T getObject() { return items.get(0); }
        @Override public T getObject(Object... args) { return items.get(0); }
        @Override public T getIfAvailable() { return items.isEmpty() ? null : items.get(0); }
        @Override public T getIfUnique() { return items.size() == 1 ? items.get(0) : null; }
        @Override public void forEach(Consumer<? super T> action) { items.forEach(action); }
        @Override public Stream<T> orderedStream() { return items.stream(); }
    }
}

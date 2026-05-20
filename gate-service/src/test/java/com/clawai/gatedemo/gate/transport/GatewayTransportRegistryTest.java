package com.clawai.gatedemo.gate.transport;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 单元测试：{@link GatewayTransportRegistry} 的 all / active / byName 行为。
 * 对应 spec「GatewayTransportRegistry 提供统一查询」。
 */
class GatewayTransportRegistryTest {

    /** 用最小化的 {@link ObjectProvider} 测试桩，仅实现 {@link #stream()}。 */
    private static class ListProvider<T> implements ObjectProvider<T> {
        private final List<T> items;
        ListProvider(List<T> items) { this.items = items; }
        @Override public Stream<T> stream() { return items.stream(); }
        @Override public T getObject() { return items.get(0); }
        @Override public T getObject(Object... args) { return items.get(0); }
        @Override public T getIfAvailable() { return items.isEmpty() ? null : items.get(0); }
        @Override public T getIfUnique() { return items.size() == 1 ? items.get(0) : null; }
        @Override public void forEach(Consumer<? super T> action) { items.forEach(action); }
        @Override public Stream<T> orderedStream() { return items.stream(); }
    }

    @Test
    void active_excludesDisabledTransports() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket", true);
        NoopGatewayTransport tcp = new NoopGatewayTransport("tcp", false);
        GatewayTransportRegistry registry = new GatewayTransportRegistry(new ListProvider<>(List.of(ws, tcp)));

        assertEquals(2, registry.all().size());
        assertEquals(1, registry.active().size());
        assertEquals("websocket", registry.active().get(0).name());
    }

    @Test
    void byName_returnsMatchOrEmpty() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket");
        NoopGatewayTransport tcp = new NoopGatewayTransport("tcp");
        GatewayTransportRegistry registry = new GatewayTransportRegistry(new ListProvider<>(List.of(ws, tcp)));

        Optional<GatewayTransport> hit = registry.byName("tcp");
        assertTrue(hit.isPresent());
        assertSame(tcp, hit.get());

        assertTrue(registry.byName("kcp").isEmpty());
        assertTrue(registry.byName(null).isEmpty());
    }

    @Test
    void allList_isImmutable() {
        NoopGatewayTransport ws = new NoopGatewayTransport("websocket");
        GatewayTransportRegistry registry = new GatewayTransportRegistry(new ListProvider<>(List.of(ws)));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.all().add(new NoopGatewayTransport("kcp")));
    }
}

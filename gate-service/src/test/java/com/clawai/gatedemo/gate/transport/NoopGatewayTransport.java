package com.clawai.gatedemo.gate.transport;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 测试用 {@link GatewayTransport} 实现：记录调用顺序、可选地在 {@link #stopAccepting()} 抛错，
 * 用于验证 {@link GatewayTransportRegistry} / {@code GracefulShutdownManager} 的多 transport 行为。
 */
public class NoopGatewayTransport implements GatewayTransport {

    private final String name;
    private final boolean enabled;
    private final boolean throwOnStopAccepting;
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.NEW);
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());

    public NoopGatewayTransport(String name) {
        this(name, true, false);
    }

    public NoopGatewayTransport(String name, boolean enabled) {
        this(name, enabled, false);
    }

    public NoopGatewayTransport(String name, boolean enabled, boolean throwOnStopAccepting) {
        this.name = name;
        this.enabled = enabled;
        this.throwOnStopAccepting = throwOnStopAccepting;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void start() {
        events.add(name + ":start");
        state.set(TransportState.RUNNING);
    }

    @Override
    public void stopAccepting() {
        events.add(name + ":stopAccepting");
        if (throwOnStopAccepting) {
            throw new RuntimeException("simulated stopAccepting failure for " + name);
        }
        state.set(TransportState.STOPPING_ACCEPT);
    }

    @Override
    public void stop() {
        events.add(name + ":stop");
        state.set(TransportState.STOPPED);
    }

    @Override
    public TransportInfo info() {
        return new TransportInfo(name, "0.0.0.0", 0, name);
    }

    @Override
    public TransportState state() {
        return state.get();
    }

    public List<String> events() {
        return events;
    }
}

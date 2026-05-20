package com.clawai.gatedemo.gate.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 自检 {@link NoopGatewayTransport} 的状态机与异常注入逻辑，
 * 保证其他依赖它的测试用例语义正确。
 */
class NoopGatewayTransportTest {

    @Test
    void lifecycle_transitionsThroughStates() {
        NoopGatewayTransport t = new NoopGatewayTransport("x");
        assertEquals(TransportState.NEW, t.state());

        t.start();
        assertEquals(TransportState.RUNNING, t.state());

        t.stopAccepting();
        assertEquals(TransportState.STOPPING_ACCEPT, t.state());

        t.stop();
        assertEquals(TransportState.STOPPED, t.state());
    }

    @Test
    void stopAccepting_throwsWhenConfigured() {
        NoopGatewayTransport t = new NoopGatewayTransport("x", true, true);
        t.start();
        assertThrows(RuntimeException.class, t::stopAccepting);
    }
}

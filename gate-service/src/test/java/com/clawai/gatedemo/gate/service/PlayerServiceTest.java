package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.FlowFeatures;
import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.flow.FlowSessionManagerTestSupport;
import com.clawai.gatedemo.gate.flow.RedisFlowStore;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * B3：{@link PlayerService#sendToPlayer} 三分支路由测试。
 *
 * <p>ATTACHED → FlowSessionManager.writeDownstream（直写 channel）；
 * DETACHED  → OfflineMessageService.storeForDetached；
 * 无 session → OfflineMessageService.storeForOffline。
 */
class PlayerServiceTest {

    private GateConfig gateConfig;
    private FlowSessionManager manager;
    private RecordingOfflineService offlineService;
    private PlayerService playerService;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-test-01");
        gateConfig.getFlow().setDetachedTtlSeconds(10);
        gateConfig.getFlow().setMaxTtlSeconds(60);
        gateConfig.getFlow().setRenewalIntervalSeconds(5);
        gateConfig.getFlow().setDetachedScanIntervalSeconds(60);

        RedisFlowStore stub = FlowSessionManagerTestSupport.inMemoryStore(gateConfig.getFlow());
        manager = new FlowSessionManager(gateConfig, stub, new SimpleMeterRegistry());
        FlowSessionManagerTestSupport.init(manager);

        offlineService = new RecordingOfflineService();
        playerService = new PlayerService(offlineService, manager);
    }

    @AfterEach
    void tearDown() {
        FlowSessionManagerTestSupport.shutdown(manager);
    }

    @Test
    void sendToPlayer_attached_routesToWriteDownstream() {
        EmbeddedChannel ch = new EmbeddedChannel();
        manager.newFlow(1L, 1, ch, FlowFeatures.GW_SEQ);

        boolean ok = playerService.sendToPlayer(1L, msg(42, new byte[]{1, 2}));

        assertTrue(ok);
        assertEquals(0, offlineService.detachedCalls.size(), "ATTACHED 不应走 offline 路径");
        assertEquals(0, offlineService.offlineCalls.size());
        Object outbound = ch.readOutbound();
        assertNotNull(outbound, "ATTACHED 直写 channel 应能读出 frame");
        assertTrue(outbound instanceof WrappedMessage);
    }

    @Test
    void sendToPlayer_detached_routesToStoreForDetached() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(2L, 1, ch, FlowFeatures.GW_SEQ);
        manager.markDetached(ch);
        assertEquals(FlowSession.State.DETACHED, s.getState());

        boolean ok = playerService.sendToPlayer(2L, msg(7, new byte[]{9}));

        assertTrue(ok);
        assertEquals(1, offlineService.detachedCalls.size());
        assertEquals(s.getFlowId(), offlineService.detachedCalls.get(0).flowId);
        assertEquals(0, offlineService.offlineCalls.size());
    }

    @Test
    void sendToPlayer_noSession_routesToStoreForOffline() {
        boolean ok = playerService.sendToPlayer(999L, msg(7, new byte[]{1}));

        assertTrue(ok);
        assertEquals(1, offlineService.offlineCalls.size());
        assertEquals(999L, offlineService.offlineCalls.get(0).playerId);
        assertEquals(0, offlineService.detachedCalls.size());
    }

    @Test
    void sendToPlayer_offlineServiceUnavailable_andDetached_returnsFalse() {
        // 不注入 offline service
        PlayerService bare = new PlayerService(null, manager);

        EmbeddedChannel ch = new EmbeddedChannel();
        manager.newFlow(3L, 1, ch);
        manager.markDetached(ch);

        boolean ok = bare.sendToPlayer(3L, msg(1, new byte[]{1}));
        assertFalse(ok);
    }

    @Test
    void sendToPlayer_offlineServiceUnavailable_andNoSession_returnsFalse() {
        PlayerService bare = new PlayerService(null, manager);
        boolean ok = bare.sendToPlayer(4L, msg(1, new byte[]{1}));
        assertFalse(ok);
    }

    @Test
    void sendToPlayer_nullPlayerId_returnsFalse() {
        assertFalse(playerService.sendToPlayer(null, msg(1, new byte[]{1})));
    }

    private WrappedMessage msg(int msgId, byte[] body) {
        MessageHeader h = new MessageHeader();
        h.setMode(MessageHeader.MODE_PUSH);
        h.setMessageId(msgId);
        return new WrappedMessage(h, new RawMessageBody(body));
    }

    /** 仅捕获 storeForDetached / storeForOffline 调用。 */
    static class RecordingOfflineService extends OfflineMessageService {
        static class DetachedCall { final String flowId; final long playerId;
            DetachedCall(String f, long p) { this.flowId = f; this.playerId = p; }
        }
        static class OfflineCall { final long playerId;
            OfflineCall(long p) { this.playerId = p; }
        }
        final List<DetachedCall> detachedCalls = new ArrayList<>();
        final List<OfflineCall> offlineCalls = new ArrayList<>();

        RecordingOfflineService() {
            super(null, null, new GateConfig(), null);
        }

        @Override
        public boolean storeForDetached(FlowSession session, WrappedMessage message) {
            detachedCalls.add(new DetachedCall(session.getFlowId(), session.getPlayerId()));
            return true;
        }

        @Override
        public boolean storeForOffline(long playerId, WrappedMessage message) {
            offlineCalls.add(new OfflineCall(playerId));
            return true;
        }
    }
}

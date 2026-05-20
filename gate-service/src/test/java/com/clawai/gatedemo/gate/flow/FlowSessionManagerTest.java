package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态机 + 顶号 + RESUME 校验 + DETACHED 扫描 单元测试。
 *
 * <p>使用 {@link EmbeddedChannel} 替代真实 Netty Channel；{@link RedisFlowStore} 用一个
 * 手写内存 stub（{@link InMemoryRedisFlowStoreStub}）替代，避免在 JDK 23 上动用 Mockito 的
 * 内联 mock-maker。
 */
class FlowSessionManagerTest {

    private GateConfig gateConfig;
    private InMemoryRedisFlowStoreStub redisStore;
    private FlowSessionManager manager;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-test-01");
        GateConfig.FlowConfig flow = gateConfig.getFlow();
        flow.setDetachedTtlSeconds(2);
        flow.setMaxTtlSeconds(60);
        flow.setRenewalIntervalSeconds(1);
        flow.setDetachedScanIntervalSeconds(60);
        flow.setRedisKeyPrefix("gate:flow:");

        redisStore = new InMemoryRedisFlowStoreStub(flow);
        manager = new FlowSessionManager(gateConfig, redisStore, new SimpleMeterRegistry());
        manager.init();
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void newFlow_mintsFreshFlowIdAndAttaches() {
        EmbeddedChannel ch = new EmbeddedChannel();

        FlowSession session = manager.newFlow(123L, 7, ch);

        assertNotNull(session.getFlowId());
        assertEquals(123L, session.getPlayerId());
        assertEquals(7, session.getGameId());
        assertEquals("gate-test-01", session.getOwnerGateId());
        assertEquals(FlowSession.State.ATTACHED, session.getState());
        assertEquals(ch, session.getCurrentChannel());
        assertEquals(session, ch.attr(FlowSession.FLOW_SESSION_KEY).get());
        assertEquals(session, manager.getByPlayerId(123L));
    }

    @Test
    void newFlow_secondLoginClosesOldChannelAndReplacesFlow() {
        EmbeddedChannel oldCh = new EmbeddedChannel();
        FlowSession first = manager.newFlow(42L, 1, oldCh);

        EmbeddedChannel newCh = new EmbeddedChannel();
        FlowSession second = manager.newFlow(42L, 1, newCh);

        assertNotEquals(first.getFlowId(), second.getFlowId());
        assertFalse(oldCh.isOpen(), "Old channel must be closed by top-out");
        assertEquals(second, manager.getByPlayerId(42L));
        assertNull(manager.getByFlowId(first.getFlowId()));
    }

    @Test
    void resume_expiredFromRedis_returnsRejectedExpired() {
        redisStore.preload(new RedisFlowRecord(
                "expired-flow", 1L, 1, "gate-test-01",
                System.currentTimeMillis() - 10_000,
                System.currentTimeMillis() - 1_000,
                0L, 0L));

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("expired-flow", 1L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_EXPIRED, result.outcome());
        assertNull(result.session());
    }

    @Test
    void resume_mismatchPlayerId_returnsRejectedMismatch() {
        redisStore.preload(new RedisFlowRecord(
                "flow-x", 100L, 1, "gate-test-01",
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000,
                0L, 0L));

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-x", 999L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_MISMATCH, result.outcome());
    }

    @Test
    void resume_ownerOnDifferentGate_crossDisabled_returnsRejectedOwnerOther() {
        // 显式关闭跨实例迁移以走 Phase A 行为
        gateConfig.getFlow().getCross().setEnabled(false);

        redisStore.preload(new RedisFlowRecord(
                "flow-y", 5L, 1, "gate-other-99",
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000,
                0L, 0L));

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-y", 5L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_OWNER_OTHER, result.outcome());
    }

    @Test
    void resume_ownerOnDifferentGate_crossUnavailable_fallsBackToOwnerOther() {
        // 跨实例开关 ON，但 Redis 返回 REDIS_UNAVAILABLE 时仍按 Phase A 拒绝（不静默装新 flow）
        gateConfig.getFlow().getCross().setEnabled(true);
        redisStore.preload(new RedisFlowRecord(
                "flow-y2", 51L, 1, "gate-other-99",
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000,
                0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.REDIS_UNAVAILABLE, null);

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-y2", 51L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_OWNER_OTHER, result.outcome());
    }

    @Test
    void resume_validSameInstance_rebindsChannelAndReturnsResumed() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession created = manager.newFlow(77L, 3, ch1);
        manager.markDetached(ch1);
        assertEquals(FlowSession.State.DETACHED, manager.getByFlowId(created.getFlowId()).getState());

        EmbeddedChannel ch2 = new EmbeddedChannel();
        // Pre-load Redis state matching the just-detached flow so resume validation passes.
        redisStore.preload(new RedisFlowRecord(
                created.getFlowId(), 77L, 3, "gate-test-01",
                created.getCreatedAt(),
                System.currentTimeMillis() + 60_000,
                0L, 0L));

        FlowSessionManager.ResumeResult result = manager.resume(created.getFlowId(), 77L, 42L, ch2);

        assertEquals(FlowResumeOutcome.RESUMED, result.outcome());
        assertNotNull(result.session());
        assertEquals(FlowSession.State.ATTACHED, result.session().getState());
        assertEquals(ch2, result.session().getCurrentChannel());
        assertEquals(42L, result.session().getLastSeqAnchor());
    }

    @Test
    void markDetached_onCurrentChannel_transitionsToDetached() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(8L, 1, ch);

        manager.markDetached(ch);

        assertEquals(FlowSession.State.DETACHED, s.getState());
        assertTrue(s.getDetachedAt() > 0);
        assertNull(s.getCurrentChannel());
    }

    @Test
    void scanDetached_pastTtl_destroysSession() throws InterruptedException {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(9L, 1, ch);
        manager.markDetached(ch);

        Thread.sleep(2_200);
        manager.debugForceScanDetached();

        assertNull(manager.getByFlowId(s.getFlowId()),
                "Flow must be destroyed after detached TTL elapses");
        assertNull(manager.getByPlayerId(9L));
    }

    @Test
    void resume_redisAbsentAndLocallyUnknown_treatedAsExpired() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("ghost-flow", 1L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_EXPIRED, result.outcome());
    }

    // ==================== B1：features 协商 / buffer / RESUME 重放 ====================

    private WrappedMessage downstream(byte[] body) {
        MessageHeader h = new MessageHeader();
        h.setMode(MessageHeader.MODE_PUSH);
        h.setMessageId(42);
        return new WrappedMessage(h, new RawMessageBody(body));
    }

    @Test
    void newFlow_withFeatures_negotiatesAndCreatesBuffer() {
        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession session = manager.newFlow(1L, 1, ch, client);

        assertEquals(client, session.getFeatures(), "服务端默认通告 0x3，应与客户端 AND 后 = 0x3");
        assertNotNull(session.getBuffer(), "协商了 GW_SEQ 则必须装 buffer");
    }

    @Test
    void writeDownstream_stampsGwSeqAndEnqueues() {
        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(2L, 1, ch, client);

        manager.writeDownstream(s, downstream(new byte[]{1, 2, 3}));
        manager.writeDownstream(s, downstream(new byte[]{4, 5, 6}));

        assertEquals(2, s.getBuffer().size());
        assertEquals(1L, s.getBuffer().minGwSeq());
        assertEquals(2L, s.getBuffer().maxGwSeq());

        Object outbound = ch.readOutbound();
        assertNotNull(outbound, "Embedded channel must receive the frame");
        assertTrue(outbound instanceof WrappedMessage);
        assertTrue(((WrappedMessage) outbound).getHeader().hasGwSeq());
    }

    @Test
    void ackSeq_trimsBufferAndAdvancesLastSeqAnchor() {
        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(3L, 1, ch, client);

        for (int i = 0; i < 10; i++) {
            manager.writeDownstream(s, downstream(new byte[]{(byte) i}));
        }
        assertEquals(10, s.getBuffer().size());

        int trimmed = manager.ackSeq(3L, 7L);
        assertEquals(7, trimmed);
        assertEquals(3, s.getBuffer().size());
        assertEquals(7L, s.getLastSeqAnchor());
    }

    @Test
    void resume_replaysPendingFrames_afterDetachAndReconnect() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(4L, 1, ch1, client);

        for (int i = 0; i < 5; i++) {
            manager.writeDownstream(s, downstream(new byte[]{(byte) i}));
        }
        // drain anything sitting in ch1's outbound to mimic «client received them»
        while (ch1.readOutbound() != null) { /* drain */ }

        manager.markDetached(ch1);

        EmbeddedChannel ch2 = new EmbeddedChannel();
        // Preload Redis with the detached flow so RESUME passes validation
        redisStore.preload(new RedisFlowRecord(s.getFlowId(), 4L, 1, "gate-test-01",
                s.getCreatedAt(), System.currentTimeMillis() + 60_000, 0L, 0L));

        // Client claims it only saw up to gwSeq=2 → should receive 3,4,5
        FlowSessionManager.ResumeResult result = manager.resume(s.getFlowId(), 4L, 2L, ch2, client);
        assertEquals(FlowResumeOutcome.RESUMED, result.outcome());
        assertEquals(3, result.replayedCount(), "should replay gwSeq 3..5");

        int observed = 0;
        Object out;
        while ((out = ch2.readOutbound()) != null) {
            observed++;
            assertTrue(out instanceof WrappedMessage);
            assertTrue(((WrappedMessage) out).getHeader().hasGwSeq());
        }
        assertEquals(3, observed, "Channel 2 should have 3 replayed frames");
    }

    @Test
    void resume_noReplayWhenClientCaughtUp() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(5L, 1, ch1, client);

        manager.writeDownstream(s, downstream(new byte[]{1}));
        manager.writeDownstream(s, downstream(new byte[]{2}));
        manager.markDetached(ch1);

        EmbeddedChannel ch2 = new EmbeddedChannel();
        redisStore.preload(new RedisFlowRecord(s.getFlowId(), 5L, 1, "gate-test-01",
                s.getCreatedAt(), System.currentTimeMillis() + 60_000, 0L, 0L));

        FlowSessionManager.ResumeResult result = manager.resume(s.getFlowId(), 5L, 2L, ch2, client);
        assertEquals(FlowResumeOutcome.RESUMED, result.outcome());
        assertEquals(0, result.replayedCount());
        assertNull(ch2.readOutbound(), "no frames should be replayed");
    }

    @Test
    void writeDownstream_oldClientSkipsBufferAndGwSeq() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(6L, 1, ch, 0); // 老客户端
        manager.writeDownstream(s, downstream(new byte[]{1}));

        assertNull(s.getBuffer(), "no buffer for old client");
        Object out = ch.readOutbound();
        assertNotNull(out);
        assertFalse(((WrappedMessage) out).getHeader().hasGwSeq());
    }

    // ==================== B2：跨实例 owner 迁移 / Pub/Sub eviction ====================

    @Test
    void resume_crossTakeover_ownerChanged_succeedsAndPublishesEvict() {
        // 跨实例开关 ON（默认）；远端 owner = gate-other-77
        redisStore.preload(new RedisFlowRecord(
                "flow-cross-1", 71L, 1, "gate-other-77",
                System.currentTimeMillis() - 5_000,
                System.currentTimeMillis() + 60_000,
                0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other-77");

        RecordingEvictPublisher publisher = new RecordingEvictPublisher();
        manager.setEvictPublisher(publisher);

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-cross-1", 71L, 0L, ch);

        assertEquals(FlowResumeOutcome.RESUMED, result.outcome());
        assertNotNull(result.session());
        assertEquals("gate-test-01", result.session().getOwnerGateId(),
                "本地 FlowSession ownerGateId 必须改写为本实例 id");
        assertEquals(ch, result.session().getCurrentChannel());

        assertEquals(1, publisher.published.size(), "owner_changed 必须发布一次 evict");
        RecordingEvictPublisher.Event ev = publisher.published.get(0);
        assertEquals("flow-cross-1", ev.flowId);
        assertEquals("gate-test-01", ev.newOwnerGateId);
        assertEquals("gate-other-77", ev.previousOwnerGateId);
    }

    @Test
    void resume_crossTakeover_ownerSame_succeedsWithoutEvict() {
        redisStore.preload(new RedisFlowRecord(
                "flow-cross-2", 72L, 1, "gate-other-77",
                System.currentTimeMillis() - 5_000,
                System.currentTimeMillis() + 60_000,
                0L, 0L));
        // SAME 表示 Lua 之后 owner 未变（幂等），不应发 evict
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_SAME, null);

        RecordingEvictPublisher publisher = new RecordingEvictPublisher();
        manager.setEvictPublisher(publisher);

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-cross-2", 72L, 0L, ch);

        assertEquals(FlowResumeOutcome.RESUMED, result.outcome());
        assertEquals(0, publisher.published.size(), "owner_same 不应触发 evict 广播");
    }

    @Test
    void resume_crossTakeover_expired_returnsExpired() {
        redisStore.preload(new RedisFlowRecord(
                "flow-cross-3", 73L, 1, "gate-other-77",
                System.currentTimeMillis() - 5_000,
                System.currentTimeMillis() + 60_000,
                0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.EXPIRED, null);

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult result = manager.resume("flow-cross-3", 73L, 0L, ch);

        assertEquals(FlowResumeOutcome.REJECTED_EXPIRED, result.outcome());
    }

    @Test
    void evictByCrossInstanceTakeover_removesLocalAndClosesChannel() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(81L, 1, ch);
        String flowId = s.getFlowId();

        boolean removed = manager.evictByCrossInstanceTakeover(flowId, "gate-other-99");

        assertTrue(removed);
        assertNull(manager.getByFlowId(flowId));
        assertNull(manager.getByPlayerId(81L));
        assertFalse(ch.isOpen(), "evict 应主动关闭老 channel");
    }

    @Test
    void evictByCrossInstanceTakeover_unknownFlow_returnsFalse() {
        boolean removed = manager.evictByCrossInstanceTakeover("nonexistent-flow", "gate-other-99");
        assertFalse(removed);
    }

    // ==================== B3：offline 合流 / flush ====================

    @Test
    void destroy_detachedTtl_flushesBufferToOfflineService() {
        RecordingOfflineService offline = new RecordingOfflineService();
        manager.setOfflineMessageService(offline);

        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(91L, 1, ch, client);
        // 写 3 条；buffer 中持有 gwSeq=1..3
        for (int i = 0; i < 3; i++) {
            manager.writeDownstream(s, downstream(new byte[]{(byte) i}));
        }
        // detached_ttl reason 应触发 flush
        manager.destroy(s, "detached_ttl");

        assertEquals(1, offline.flushCalls.size(), "detached_ttl 应触发一次 flushBufferToOffline");
        assertEquals(s.getFlowId(), offline.flushCalls.get(0).flowId);
        assertEquals("detached_ttl", offline.flushCalls.get(0).reason);
    }

    @Test
    void destroy_newTakeover_doesNotFlush() {
        RecordingOfflineService offline = new RecordingOfflineService();
        manager.setOfflineMessageService(offline);

        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ;
        FlowSession s = manager.newFlow(92L, 1, ch, client);
        manager.writeDownstream(s, downstream(new byte[]{1}));
        // 顶号
        EmbeddedChannel ch2 = new EmbeddedChannel();
        manager.newFlow(92L, 1, ch2, client);

        // newFlow 内部走 evictLocal → emit destroyed reason=new_takeover；不应进入 destroy(...) 路径
        assertEquals(0, offline.flushCalls.size(), "new_takeover 不应 flush");
    }

    @Test
    void evictByCrossInstanceTakeover_flushesBufferBeforeEvict() {
        RecordingOfflineService offline = new RecordingOfflineService();
        manager.setOfflineMessageService(offline);

        EmbeddedChannel ch = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ;
        FlowSession s = manager.newFlow(93L, 1, ch, client);
        manager.writeDownstream(s, downstream(new byte[]{1}));
        manager.writeDownstream(s, downstream(new byte[]{2}));

        boolean removed = manager.evictByCrossInstanceTakeover(s.getFlowId(), "gate-other-99");

        assertTrue(removed);
        assertEquals(1, offline.flushCalls.size(), "cross_takeover 应触发一次 flush");
        assertEquals("cross_takeover", offline.flushCalls.get(0).reason);
    }

    @Test
    void resume_callsOfflineReplayAndMerge_andCombinesCounts() {
        RecordingOfflineService offline = new RecordingOfflineService();
        offline.nextReplayResult = new com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult(2, 5L, 7L);
        manager.setOfflineMessageService(offline);

        EmbeddedChannel ch1 = new EmbeddedChannel();
        int client = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        FlowSession s = manager.newFlow(94L, 1, ch1, client);
        // 写 5 帧；客户端 ACK=3，buffer 剩 4,5
        for (int i = 0; i < 5; i++) {
            manager.writeDownstream(s, downstream(new byte[]{(byte) i}));
        }
        manager.ackSeq(94L, 3L);
        manager.markDetached(ch1);

        EmbeddedChannel ch2 = new EmbeddedChannel();
        redisStore.preload(new RedisFlowRecord(s.getFlowId(), 94L, 1, "gate-test-01",
                s.getCreatedAt(), System.currentTimeMillis() + 60_000, 0L, 3L));

        FlowSessionManager.ResumeResult r = manager.resume(s.getFlowId(), 94L, 3L, ch2, client);

        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());
        assertEquals(2 + 2, r.replayedCount(), "buffer 重放 2 (gwSeq=4,5) + offline 重放 2 (mocked) = 4");
        assertEquals(7L, r.replayToSeq(), "offline.toSeq=7 应覆盖 buffer.toSeq=5");
        assertEquals(1, offline.replayCalls.size());
        // offline fromSeq 应为 max(lastClientRecvSeq=3, bufferReplay.toSeq=5) = 5
        assertEquals(5L, offline.replayCalls.get(0).fromSeq);
    }

    @Test
    void resume_crossInstance_seedsNextGwSeqFromOffline() {
        RecordingOfflineService offline = new RecordingOfflineService();
        offline.fixedMaxGwSeq = 42L;
        manager.setOfflineMessageService(offline);

        redisStore.preload(new RedisFlowRecord(
                "flow-seed", 101L, 1, "gate-other-77",
                System.currentTimeMillis() - 5_000,
                System.currentTimeMillis() + 60_000,
                0L, 7L));   // anchor=7
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other-77");

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult r = manager.resume("flow-seed", 101L, 0L, ch);

        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());
        FlowSession local = manager.getByFlowId("flow-seed");
        assertNotNull(local);
        // seed = max(anchor=7, offlineMax=42) = 42 → next increment 应为 43
        assertEquals(43L, local.incrementAndGetGwSeq());
    }

    /**
     * 测试用：记录 flushBufferToOffline / replayAndMerge / maxGwSeq 调用。
     */
    static class RecordingOfflineService extends com.clawai.gatedemo.gate.service.OfflineMessageService {
        static class FlushCall { final String flowId; final String reason;
            FlushCall(String f, String r) { this.flowId = f; this.reason = r; }
        }
        static class ReplayCall { final String flowId; final long fromSeq;
            ReplayCall(String f, long s) { this.flowId = f; this.fromSeq = s; }
        }
        final java.util.List<FlushCall> flushCalls = new java.util.ArrayList<>();
        final java.util.List<ReplayCall> replayCalls = new java.util.ArrayList<>();
        volatile com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult nextReplayResult;
        volatile long fixedMaxGwSeq = 0L;

        RecordingOfflineService() {
            super(null, null, new GateConfig(), null);
        }

        @Override
        public boolean storeForDetached(FlowSession session, WrappedMessage message) { return true; }

        @Override
        public boolean storeForOffline(long playerId, WrappedMessage message) { return true; }

        @Override
        public int flushBufferToOffline(FlowSession session, String reason) {
            flushCalls.add(new FlushCall(session.getFlowId(), reason));
            return 0;
        }

        @Override
        public com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult replayAndMerge(
                FlowSession session, io.netty.channel.Channel channel, long fromSeq) {
            replayCalls.add(new ReplayCall(session.getFlowId(), fromSeq));
            return nextReplayResult != null ? nextReplayResult
                    : com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult.empty(fromSeq);
        }

        @Override
        public long maxGwSeq(String flowId) { return fixedMaxGwSeq; }
    }

    @Test
    void evictByCrossInstanceTakeover_byPlayerGuard_keepsMappingIfPlayerRebound() {
        // 场景：本地 byplayer 已被新 flow 顶替，此时收到旧 flow 的 evict —— 不能误删新的 byplayer 映射
        EmbeddedChannel chOld = new EmbeddedChannel();
        FlowSession sOld = manager.newFlow(82L, 1, chOld);
        String oldFlowId = sOld.getFlowId();

        EmbeddedChannel chNew = new EmbeddedChannel();
        FlowSession sNew = manager.newFlow(82L, 1, chNew); // 顶号，覆盖 byplayer
        assertNotEquals(oldFlowId, sNew.getFlowId());

        // 二次 NEW 已经 evictLocal 了 oldFlowId，flowsById 中已经不存在
        // 模拟旧实例的 evict 事件「迟到」
        boolean removed = manager.evictByCrossInstanceTakeover(oldFlowId, "gate-other-99");

        assertFalse(removed, "旧 flow 已被顶号清理，evict 应返回 false");
        assertEquals(sNew, manager.getByPlayerId(82L),
                "byplayer 必须保持指向新 flow（不能被旧 flow evict 误删）");
        assertTrue(chNew.isOpen(), "新 channel 必须保持开启");
    }

    // ==================== add-flow-observability-buckets：新指标契约测试 ====================

    @Test
    void resume_sameGw_emitsResumeTotalAndLatency_kindSameGw() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession s = manager.newFlow(201L, 1, ch1);
        // 触发同实例 RESUME（local 已存在，record 也存在 owner=self）
        EmbeddedChannel ch2 = new EmbeddedChannel();
        FlowSessionManager.ResumeResult r = manager.resume(s.getFlowId(), 201L, 0L, ch2);
        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());

        // 同 kind=same_gw, outcome=succeeded counter +1
        assertEquals(1.0,
                findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                        .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_SAME_GW)
                        .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_SUCCEEDED)
                        .counter().count());
        // latency timer 至少有一次记录
        assertTrue(findRegistry().find(FlowMetrics.M_LATENCY_RESUME)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_SAME_GW)
                .timer().count() >= 1);
    }

    @Test
    void resume_crossGw_emitsResumeTotalAndBothTimers_kindCrossGw() {
        redisStore.preload(new RedisFlowRecord(
                "flow-cgw", 202L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other-77");

        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSessionManager.ResumeResult r = manager.resume("flow-cgw", 202L, 0L, ch);
        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());

        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_CROSS_GW)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_SUCCEEDED)
                .counter().count());
        assertTrue(findRegistry().find(FlowMetrics.M_LATENCY_RESUME)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_CROSS_GW)
                .timer().count() >= 1);
        assertTrue(findRegistry().find(FlowMetrics.M_LATENCY_CROSS_READY)
                .timer().count() >= 1);
        // cross-gw 触发 takeover 成功 + publish 发起方 emit evicted_remote 是 publisher 的事，这里 publisher 未注入 → 不验证
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_SUCCEEDED).counter().count());
    }

    @Test
    void resume_rejectedExpired_emitsOutcomeRejectedExpired() {
        redisStore.preload(new RedisFlowRecord(
                "flow-exp", 203L, 1, "gate-test-01",
                System.currentTimeMillis() - 10_000,
                System.currentTimeMillis() - 1_000, 0L, 0L));

        FlowSessionManager.ResumeResult r = manager.resume("flow-exp", 203L, 0L, new EmbeddedChannel());
        assertEquals(FlowResumeOutcome.REJECTED_EXPIRED, r.outcome());

        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_REJECTED_EXPIRED)
                .counter().count());
    }

    @Test
    void resume_rejectedMismatch_emitsOutcomeRejectedMismatch() {
        redisStore.preload(new RedisFlowRecord(
                "flow-mis", 100L, 1, "gate-test-01",
                System.currentTimeMillis(),
                System.currentTimeMillis() + 60_000, 0L, 0L));

        FlowSessionManager.ResumeResult r = manager.resume("flow-mis", 999L, 0L, new EmbeddedChannel());
        assertEquals(FlowResumeOutcome.REJECTED_MISMATCH, r.outcome());

        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_REJECTED_MISMATCH)
                .counter().count());
    }

    @Test
    void resume_exceededTimeoutMs_emitsOutcomeTimeout() {
        // 把 timeout 设到 1ms；让 offline stub 在 replayAndMerge 内 sleep 20ms 保证总耗时超阈值
        gateConfig.getFlow().getObservability().setResumeTimeoutMs(1);
        manager.shutdown();
        manager = new FlowSessionManager(gateConfig, redisStore, new SimpleMeterRegistry());
        manager.init();

        com.clawai.gatedemo.gate.service.OfflineMessageService slowOffline =
                new RecordingOfflineService() {
            @Override
            public com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult replayAndMerge(
                    FlowSession session, io.netty.channel.Channel channel, long fromSeq) {
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                return com.clawai.gatedemo.gate.service.OfflineMessageService.ReplayResult.empty(fromSeq);
            }
        };
        manager.setOfflineMessageService(slowOffline);

        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession s = manager.newFlow(204L, 1, ch1);
        FlowSessionManager.ResumeResult r = manager.resume(s.getFlowId(), 204L, 0L, new EmbeddedChannel());
        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());

        assertNotNull(findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_TIMEOUT).counter(),
                "RESUME 总耗时 > 1ms 时应 emit outcome=timeout 而非 succeeded");
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_TIMEOUT).counter().count());
    }

    @Test
    void takeover_ownerSame_emitsResultSucceeded() {
        redisStore.preload(new RedisFlowRecord(
                "flow-osm", 205L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_SAME, null);

        manager.resume("flow-osm", 205L, 0L, new EmbeddedChannel());
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_SUCCEEDED).counter().count());
    }

    @Test
    void takeover_expired_emitsResultMetadataMissing() {
        redisStore.preload(new RedisFlowRecord(
                "flow-exm", 206L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.EXPIRED, null);

        manager.resume("flow-exm", 206L, 0L, new EmbeddedChannel());
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_METADATA_MISSING).counter().count());
    }

    @Test
    void takeover_redisUnavailable_emitsResultRedisUnavailable() {
        redisStore.preload(new RedisFlowRecord(
                "flow-rum", 207L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.REDIS_UNAVAILABLE, null);

        manager.resume("flow-rum", 207L, 0L, new EmbeddedChannel());
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_TAKEOVER_TOTAL)
                .tag(FlowMetrics.T_RESULT, FlowMetrics.RESULT_REDIS_UNAVAILABLE).counter().count());
    }

    @Test
    void newFlow_emitsKindNewOutcomeSucceeded() {
        manager.newFlow(208L, 1, new EmbeddedChannel());
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_NEW)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_SUCCEEDED)
                .counter().count());
        assertTrue(findRegistry().find(FlowMetrics.M_LATENCY_RESUME)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_NEW).timer().count() >= 1);
    }

    @Test
    void newFlowAfterReject_emitsKindNewOutcomeDegradedToNew_notSucceeded() {
        manager.newFlowAfterReject(209L, 1, new EmbeddedChannel(),
                FlowResumeOutcome.REJECTED_EXPIRED);
        assertEquals(1.0, findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_NEW)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_DEGRADED_TO_NEW)
                .counter().count());
        assertNull(findRegistry().find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_NEW)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.OUTCOME_SUCCEEDED)
                .counter(),
                "降级路径与普通 NEW 互斥，不应 emit succeeded");
    }

    @Test
    void replayPending_emitsBufferReplayedAndSkipped() {
        int features = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession s = manager.newFlow(210L, 1, ch1, features);
        for (int i = 0; i < 5; i++) {
            manager.writeDownstream(s, downstream(new byte[]{(byte) i}));
        }
        // detach
        manager.markDetached(ch1);
        // RESUME 带 ACK=3 → trim 3 帧 (skipped_acked)，重放 2 帧 (replayed)
        EmbeddedChannel ch2 = new EmbeddedChannel();
        FlowSessionManager.ResumeResult r = manager.resume(s.getFlowId(), 210L, 3L, ch2, features);
        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());

        assertEquals(3.0, findRegistry().find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_BUFFER)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_SKIPPED_ACKED)
                .counter().count(), "ACK=3 → 3 帧被裁剪计为 skipped_acked");
        assertEquals(2.0, findRegistry().find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_BUFFER)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_REPLAYED)
                .counter().count(), "剩余 2 帧重放到新 channel 计为 replayed");
    }

    @Test
    void legacyMetricsNotEmitted_oldNamesAreFullyDecommissioned() {
        // 跑一个完整的 cross-gw RESUME + buffer replay 路径
        int features = FlowFeatures.GW_SEQ | FlowFeatures.RESUME_REPLAY;
        redisStore.preload(new RedisFlowRecord(
                "flow-legacy", 211L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other-77");
        manager.resume("flow-legacy", 211L, 0L, new EmbeddedChannel(), features);

        // 旧 metric 必须不在 registry 中
        assertNull(findRegistry().find("gate_flow_resume_latency_ms").timer(),
                "旧 untagged Timer 已下线");
        assertNull(findRegistry().find("gate_flow_buffer_replay_total").counter(),
                "旧 buffer replay counter 已下线");
        // 旧 gate_flow_total{event=cross_takeover} 不应被 emit（保留其他 event 子集）
        assertNull(findRegistry().find("gate_flow_total")
                        .tag("event", "cross_takeover").counter(),
                "旧 gate_flow_total{event=cross_takeover} 已下线");
    }

    @Test
    void lifecycleCountersSurvive_afterObservabilityChange() {
        EmbeddedChannel ch1 = new EmbeddedChannel();
        FlowSession s = manager.newFlow(212L, 1, ch1);
        EmbeddedChannel ch2 = new EmbeddedChannel();
        manager.resume(s.getFlowId(), 212L, 0L, ch2);

        // lifecycle counter 还在
        assertTrue(findRegistry().find("gate_flow_total")
                .tag("event", "new").counter().count() >= 1.0);
        assertTrue(findRegistry().find("gate_flow_total")
                .tag("event", "resumed").counter().count() >= 1.0);
    }

    @Test
    void destroyedReasonCrossTakeoverSurvives() {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = manager.newFlow(213L, 1, ch);
        manager.evictByCrossInstanceTakeover(s.getFlowId(), "gate-other-99");

        // lifecycle counter destroyed{reason=cross_takeover} 还在
        assertEquals(1.0, findRegistry().find("gate_flow_total")
                .tag("event", "destroyed").tag("reason", "cross_takeover")
                .counter().count());
    }

    @Test
    void observabilityDisabled_disablesAllNewMetrics() {
        gateConfig.getFlow().getObservability().setEnabled(false);
        manager.shutdown();
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        manager = new FlowSessionManager(gateConfig, redisStore, reg);
        manager.init();

        manager.newFlow(214L, 1, new EmbeddedChannel());
        // 所有新 metric 都不应注册
        assertNull(reg.find(FlowMetrics.M_TAKEOVER_TOTAL).counter());
        assertNull(reg.find(FlowMetrics.M_RESUME_TOTAL).counter());
        assertNull(reg.find(FlowMetrics.M_REPLAY_TOTAL).counter());
        assertNull(reg.find(FlowMetrics.M_LATENCY_RESUME).timer());
        assertNull(reg.find(FlowMetrics.M_LATENCY_CROSS_READY).timer());
    }

    @Test
    void takeoverTotalDisabled_otherMetricsStillWork() {
        gateConfig.getFlow().getObservability().setTakeoverTotalEnabled(false);
        manager.shutdown();
        SimpleMeterRegistry reg = new SimpleMeterRegistry();
        manager = new FlowSessionManager(gateConfig, redisStore, reg);
        manager.init();

        redisStore.preload(new RedisFlowRecord(
                "flow-pms", 215L, 1, "gate-other-77",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, 0L, 0L));
        redisStore.setCrossOutcome(RedisFlowStore.CrossState.RESUMED_OWNER_CHANGED, "gate-other-77");
        FlowSessionManager.ResumeResult r = manager.resume("flow-pms", 215L, 0L, new EmbeddedChannel());
        assertEquals(FlowResumeOutcome.RESUMED, r.outcome());

        assertNull(reg.find(FlowMetrics.M_TAKEOVER_TOTAL).counter(),
                "takeoverTotal 禁用后该 counter 不应存在");
        assertNotNull(reg.find(FlowMetrics.M_RESUME_TOTAL)
                .tag(FlowMetrics.T_KIND, FlowMetrics.KIND_CROSS_GW).counter(),
                "其他 metric 仍正常工作");
        assertNotNull(reg.find(FlowMetrics.M_LATENCY_CROSS_READY).timer(),
                "其他 metric 仍正常工作");
    }

    /** 测试辅助：从 manager 的 MeterRegistry 取出 SimpleMeterRegistry 句柄。 */
    private SimpleMeterRegistry findRegistry() {
        // manager 用 SimpleMeterRegistry 构造；通过反射或 manager.flowMetrics() 间接拿
        // 简单做法：测试里始终通过 manager 自己持有的 registry 直接断言（构造时传入的就是它）
        // 这里返回 setUp 中给 manager 用的那个，避免反射；
        // 注：setUp 每次都 new 新的 SimpleMeterRegistry → 取 manager 的 meterRegistry 字段即可
        try {
            java.lang.reflect.Field f = FlowSessionManager.class.getDeclaredField("meterRegistry");
            f.setAccessible(true);
            return (SimpleMeterRegistry) f.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 测试用：捕获 publish 调用，验证 evict 广播逻辑。
     */
    static class RecordingEvictPublisher extends FlowEvictPublisher {
        static class Event {
            final String flowId;
            final String newOwnerGateId;
            final String previousOwnerGateId;
            Event(String f, String n, String p) {
                this.flowId = f; this.newOwnerGateId = n; this.previousOwnerGateId = p;
            }
        }
        final java.util.List<Event> published = new java.util.ArrayList<>();

        RecordingEvictPublisher() {
            super(null, new GateConfig(), null);
        }

        @Override
        public void publish(String flowId, String newOwnerGateId, String previousOwnerGateId) {
            published.add(new Event(flowId, newOwnerGateId, previousOwnerGateId));
        }
    }

    /**
     * 内存版 RedisFlowStore stub：实现「单玩家单 flow」的最小子集，便于 manager 单测。
     * <p>{@code public} 以便跨包测试（如 PlayerServiceTest）通过 {@link FlowSessionManagerTestSupport} 使用。
     */
    public static class InMemoryRedisFlowStoreStub extends RedisFlowStore {
        private final ConcurrentMap<String, RedisFlowRecord> flows = new ConcurrentHashMap<>();
        private final ConcurrentMap<Long, String> byPlayer = new ConcurrentHashMap<>();
        /** B2 测试钩子：下一次 crossTakeover 的强制返回值（null = 用默认 SAME 行为）。 */
        private volatile CrossState forcedCrossState;
        private volatile String forcedPreviousOwner;

        public InMemoryRedisFlowStoreStub(GateConfig.FlowConfig flowConfig) {
            super(flowConfig);
        }

        void preload(RedisFlowRecord record) {
            flows.put(record.flowId(), record);
            byPlayer.put(record.playerId(), record.flowId());
        }

        void setCrossOutcome(CrossState state, String previousOwner) {
            this.forcedCrossState = state;
            this.forcedPreviousOwner = previousOwner;
        }

        @Override
        public CrossTakeoverResult crossTakeover(String flowId, long playerId,
                                                 String newOwnerGateId, long newExpiresAtMs) {
            CrossState state = forcedCrossState;
            if (state == null) {
                // 默认：按 SAME 处理（不发广播），但同时更新内存中的 owner 与 expiresAt
                state = CrossState.RESUMED_OWNER_SAME;
            }
            switch (state) {
                case EXPIRED:
                    return CrossTakeoverResult.expired();
                case REDIS_UNAVAILABLE:
                    return CrossTakeoverResult.unavailable();
                case RESUMED_OWNER_SAME: {
                    RedisFlowRecord cur = flows.get(flowId);
                    if (cur != null) {
                        flows.put(flowId, new RedisFlowRecord(cur.flowId(), cur.playerId(), cur.gameId(),
                                newOwnerGateId, cur.createdAt(), newExpiresAtMs,
                                cur.detachedAt(), cur.lastSeqAnchor()));
                    }
                    return new CrossTakeoverResult(true, newOwnerGateId, CrossState.RESUMED_OWNER_SAME);
                }
                case RESUMED_OWNER_CHANGED:
                default: {
                    RedisFlowRecord cur = flows.get(flowId);
                    String prevOwner = forcedPreviousOwner != null ? forcedPreviousOwner
                            : (cur != null ? cur.ownerGateId() : "unknown");
                    if (cur != null) {
                        flows.put(flowId, new RedisFlowRecord(cur.flowId(), cur.playerId(), cur.gameId(),
                                newOwnerGateId, cur.createdAt(), newExpiresAtMs,
                                cur.detachedAt(), cur.lastSeqAnchor()));
                    }
                    return new CrossTakeoverResult(true, prevOwner, CrossState.RESUMED_OWNER_CHANGED);
                }
            }
        }

        @Override
        public TakeoverResult takeover(RedisFlowRecord newRecord) {
            String previous = byPlayer.put(newRecord.playerId(), newRecord.flowId());
            if (previous != null && !previous.equals(newRecord.flowId())) {
                flows.remove(previous);
            }
            flows.put(newRecord.flowId(), newRecord);
            String evicted = (previous != null && !previous.equals(newRecord.flowId())) ? previous : null;
            return new TakeoverResult(true, evicted);
        }

        @Override
        public RedisFlowRecord loadByFlowId(String flowId) {
            return flows.get(flowId);
        }

        @Override
        public String loadFlowIdByPlayer(long playerId) {
            return byPlayer.get(playerId);
        }

        @Override
        public boolean markAckedSeq(String flowId, long ackedSeq) {
            RedisFlowRecord cur = flows.get(flowId);
            if (cur == null) return false;
            flows.put(flowId, new RedisFlowRecord(cur.flowId(), cur.playerId(), cur.gameId(),
                    cur.ownerGateId(), cur.createdAt(), cur.expiresAt(), cur.detachedAt(), ackedSeq));
            return true;
        }

        @Override
        public boolean markDetached(String flowId, long detachedAt) {
            RedisFlowRecord cur = flows.get(flowId);
            if (cur == null) return false;
            flows.put(flowId, new RedisFlowRecord(cur.flowId(), cur.playerId(), cur.gameId(),
                    cur.ownerGateId(), cur.createdAt(), cur.expiresAt(), detachedAt, cur.lastSeqAnchor()));
            return true;
        }

        @Override
        public boolean renew(String flowId, long playerId, long expiresAt) {
            RedisFlowRecord cur = flows.get(flowId);
            if (cur == null) return false;
            flows.put(flowId, new RedisFlowRecord(cur.flowId(), cur.playerId(), cur.gameId(),
                    cur.ownerGateId(), cur.createdAt(), expiresAt, cur.detachedAt(), cur.lastSeqAnchor()));
            return true;
        }

        @Override
        public boolean destroy(String flowId, long playerId) {
            flows.remove(flowId);
            if (flowId.equals(byPlayer.get(playerId))) {
                byPlayer.remove(playerId);
            }
            return true;
        }

        @Override
        public boolean ping() { return true; }

        Map<Long, String> snapshotByPlayer() { return new HashMap<>(byPlayer); }
    }
}

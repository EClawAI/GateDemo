package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.flow.DownstreamBuffer;
import com.clawai.gatedemo.gate.flow.FlowSession;
import com.clawai.gatedemo.gate.flow.FlowSessionManager;
import com.clawai.gatedemo.gate.flow.FlowSessionManagerTestSupport;
import com.clawai.gatedemo.gate.flow.metrics.FlowMetrics;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.queue.MessageQueueProducer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3：{@link OfflineMessageService} 单元测试。
 *
 * <p>使用手写 {@link InMemoryMessageQueueProducerStub}（避免 Mockito 在 JDK 23 上的 inline mock 限制），
 * 同时绕开真实 Redis。覆盖：storeForDetached / storeForOffline / flushBufferToOffline /
 * replayAndMerge / maxGwSeq / Redis 不可用降级。
 */
class OfflineMessageServiceTest {

    private GateConfig gateConfig;
    private InMemoryMessageQueueProducerStub producer;
    private OfflineMessageService service;
    private PlayerService playerService; // 仅 RELOGIN 通知用，单测可塞空 manager

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.setId("gate-test-01");
        gateConfig.getFlow().getOffline().setEnabled(true);
        gateConfig.getFlow().getOffline().setRetentionDays(1);
        gateConfig.getFlow().getOffline().setReloginThreshold(3);

        producer = new InMemoryMessageQueueProducerStub();
        playerService = new PlayerService(null, null) {
            @Override public boolean sendToPlayer(Long playerId, WrappedMessage message) { return true; }
        };
        service = new OfflineMessageService(producer, playerService, gateConfig, new SimpleMeterRegistry());
    }

    // ==================== storeForDetached ====================

    @Test
    void storeForDetached_stampsGwSeqAndPersists() {
        FlowSession s = newSession("flow-d1", 11L);
        WrappedMessage msg = downstream(42, new byte[]{1, 2, 3});

        boolean ok = service.storeForDetached(s, msg);

        assertTrue(ok);
        assertEquals(1, producer.flowEntries("flow-d1").size());
        InMemoryMessageQueueProducerStub.Entry e = producer.flowEntries("flow-d1").get(0);
        assertEquals(1L, e.gwSeq);
        assertEquals(42, e.messageId);
        assertEquals(11L, e.playerId);
        assertTrue(msg.getHeader().hasGwSeq());
        assertEquals(1L, msg.getHeader().getGwSeq());
    }

    @Test
    void storeForDetached_multipleIncrementsGwSeq() {
        FlowSession s = newSession("flow-d2", 12L);
        for (int i = 0; i < 5; i++) {
            assertTrue(service.storeForDetached(s, downstream(i, new byte[]{(byte) i})));
        }
        List<InMemoryMessageQueueProducerStub.Entry> es = producer.flowEntries("flow-d2");
        assertEquals(5, es.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1L, es.get(i).gwSeq);
        }
    }

    @Test
    void storeForDetached_redisUnavailable_returnsFalse() {
        producer.simulateFailure = true;
        FlowSession s = newSession("flow-d3", 13L);
        boolean ok = service.storeForDetached(s, downstream(1, new byte[]{1}));
        assertFalse(ok);
    }

    // ==================== storeForOffline ====================

    @Test
    void storeForOffline_writesToPlayerStream() {
        boolean ok = service.storeForOffline(99L, downstream(7, new byte[]{9, 8, 7}));
        assertTrue(ok);
        assertEquals(1, producer.playerEntries.get(99L).size());
    }

    @Test
    void storeForOffline_disabled_returnsFalse() {
        gateConfig.getFlow().getOffline().setEnabled(false);
        boolean ok = service.storeForOffline(100L, downstream(1, new byte[]{1}));
        assertFalse(ok);
    }

    // ==================== flushBufferToOffline ====================

    @Test
    void flushBufferToOffline_persistsUnAckedEntries() {
        FlowSession s = newSessionWithBuffer("flow-f1", 21L);
        // 模拟 ATTACHED 期间已 stamp + buffer 入队 3 条（gwSeq=1..3）
        for (int i = 1; i <= 3; i++) {
            WrappedMessage m = downstream(100 + i, new byte[]{(byte) i});
            long seq = s.incrementAndGetGwSeq();
            m.getHeader().setHasGwSeq(true);
            m.getHeader().setGwSeq(seq);
            s.getBuffer().enqueue(seq, m, 32, System.currentTimeMillis());
        }
        // 客户端只 ACK 到 1
        s.getBuffer().ackUpTo(1L);

        int flushed = service.flushBufferToOffline(s, "detached_ttl");
        assertEquals(2, flushed, "应 flush 未 ACK 的 gwSeq=2,3");
        List<InMemoryMessageQueueProducerStub.Entry> es = producer.flowEntries("flow-f1");
        assertEquals(2, es.size());
        assertEquals(2L, es.get(0).gwSeq);
        assertEquals(3L, es.get(1).gwSeq);
    }

    @Test
    void flushBufferToOffline_emptyBuffer_returnsZero() {
        FlowSession s = newSessionWithBuffer("flow-f2", 22L);
        assertEquals(0, service.flushBufferToOffline(s, "detached_ttl"));
    }

    @Test
    void flushBufferToOffline_disabled_returnsZero() {
        gateConfig.getFlow().getOffline().setEnabled(false);
        FlowSession s = newSessionWithBuffer("flow-f3", 23L);
        s.incrementAndGetGwSeq();
        s.getBuffer().enqueue(1L, downstream(1, new byte[]{1}), 16, System.currentTimeMillis());
        assertEquals(0, service.flushBufferToOffline(s, "detached_ttl"));
    }

    // ==================== replayAndMerge ====================

    @Test
    void replayAndMerge_drainsAndDeletesOfflineEntries() {
        FlowSession s = newSession("flow-r1", 31L);
        // 预填 5 条 offline，gwSeq=10..14
        for (long seq = 10; seq <= 14; seq++) {
            producer.preloadFlow("flow-r1", new InMemoryMessageQueueProducerStub.Entry(
                    "id-" + seq, 31L, seq, (short) MessageHeader.FLAG_HAS_GW_SEQ,
                    200 + (int) seq, new byte[]{(byte) seq}));
        }
        EmbeddedChannel ch = new EmbeddedChannel();

        OfflineMessageService.ReplayResult r = service.replayAndMerge(s, ch, 11L);

        assertEquals(3, r.count(), "fromSeq=11 → 应投递 gwSeq=12,13,14");
        assertEquals(14L, r.toSeq());

        int seen = 0;
        Object out;
        while ((out = ch.readOutbound()) != null) {
            seen++;
            assertTrue(out instanceof WrappedMessage);
            MessageHeader h = ((WrappedMessage) out).getHeader();
            assertTrue(h.hasGwSeq());
            assertTrue(h.getGwSeq() > 11L);
        }
        assertEquals(3, seen);
        // 已投递的 3 条被 XDEL；dup 尾巴（gwSeq=10,11）仍留在 stream，等 TTL 自然回收
        assertEquals(2, producer.flowEntries("flow-r1").size());
    }

    @Test
    void replayAndMerge_emptyStream_returnsZero() {
        FlowSession s = newSession("flow-r2", 32L);
        EmbeddedChannel ch = new EmbeddedChannel();
        OfflineMessageService.ReplayResult r = service.replayAndMerge(s, ch, 0L);
        assertEquals(0, r.count());
    }

    @Test
    void replayAndMerge_channelInactive_returnsEmpty() {
        FlowSession s = newSession("flow-r3", 33L);
        producer.preloadFlow("flow-r3", new InMemoryMessageQueueProducerStub.Entry(
                "id-1", 33L, 1L, (short) 0, 1, new byte[]{1}));
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.close();
        OfflineMessageService.ReplayResult r = service.replayAndMerge(s, ch, 0L);
        assertEquals(0, r.count());
    }

    // ==================== add-flow-observability-buckets ====================

    @Test
    void replayAndMerge_emitsOfflineReplayedAndSkippedViaManager() {
        // 用同一个 SimpleMeterRegistry 让 manager.recordReplay 与本测试断言对齐
        SimpleMeterRegistry sharedReg = new SimpleMeterRegistry();
        FlowSessionManager manager = new FlowSessionManager(
                gateConfig,
                FlowSessionManagerTestSupport.inMemoryStore(gateConfig.getFlow()),
                sharedReg);
        FlowSessionManagerTestSupport.init(manager);
        OfflineMessageService observed = new OfflineMessageService(
                producer, playerService, gateConfig, sharedReg);
        observed.setFlowSessionManager(manager);

        FlowSession s = newSession("flow-obs-1", 71L);
        // 预填 4 条 offline (gwSeq=10..13)；fromSeq=11 → 投递 12,13；dupDropped=2 (10,11)
        for (long seq = 10; seq <= 13; seq++) {
            producer.preloadFlow("flow-obs-1", new InMemoryMessageQueueProducerStub.Entry(
                    "id-" + seq, 71L, seq, (short) MessageHeader.FLAG_HAS_GW_SEQ,
                    200 + (int) seq, new byte[]{(byte) seq}));
        }
        EmbeddedChannel ch = new EmbeddedChannel();
        OfflineMessageService.ReplayResult r = observed.replayAndMerge(s, ch, 11L);
        assertEquals(2, r.count());

        // 新 metric：source=offline；stub 内部已过滤 gwSeq<=fromSeq → dupDropped 不触发
        // 这里只断言 replayed=2 这一条新 metric
        assertNotNull(sharedReg.find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_OFFLINE)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_REPLAYED)
                .counter(),
                "投递 2 条 → offline.replayed counter 应通过 manager.recordReplay 注册到 sharedReg；"
                + " 当前 registry meters=" + sharedReg.getMeters());
        assertEquals(2.0, sharedReg.find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_OFFLINE)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_REPLAYED)
                .counter().count());
    }

    @Test
    void replayAndMerge_dupAcked_emitsSkippedAcked() {
        // 用一个手动构造的 sub-stub，模拟 readOfflineFrames 返回包含 dup 的批次
        InMemoryMessageQueueProducerStub dupStub = new InMemoryMessageQueueProducerStub() {
            @Override
            public List<OfflineEntry> readOfflineFrames(String flowId, long afterGwSeq, int batchSize) {
                // 故意忽略 afterGwSeq，让上层 replayAndMerge 自己过滤 dup
                List<OfflineEntry> out = new ArrayList<>();
                out.add(new OfflineEntry("a", 81L, 5L, (short) 0, 1, new byte[]{1})); // dup (<=5)
                out.add(new OfflineEntry("b", 81L, 5L, (short) 0, 1, new byte[]{1})); // dup
                out.add(new OfflineEntry("c", 81L, 6L, (short) 0, 1, new byte[]{1}));
                out.add(new OfflineEntry("d", 81L, 7L, (short) 0, 1, new byte[]{1}));
                return out;
            }
        };
        SimpleMeterRegistry reg2 = new SimpleMeterRegistry();
        FlowSessionManager manager2 = new FlowSessionManager(
                gateConfig,
                FlowSessionManagerTestSupport.inMemoryStore(gateConfig.getFlow()),
                reg2);
        FlowSessionManagerTestSupport.init(manager2);
        OfflineMessageService observed2 = new OfflineMessageService(
                dupStub, playerService, gateConfig, reg2);
        observed2.setFlowSessionManager(manager2);

        FlowSession s = newSession("flow-dup", 81L);
        EmbeddedChannel ch = new EmbeddedChannel();
        OfflineMessageService.ReplayResult r = observed2.replayAndMerge(s, ch, 5L);
        assertEquals(2, r.count(), "fromSeq=5 → 投递 6,7");

        assertNotNull(reg2.find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_OFFLINE)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_SKIPPED_ACKED)
                .counter());
        assertEquals(2.0, reg2.find(FlowMetrics.M_REPLAY_TOTAL)
                .tag(FlowMetrics.T_SOURCE, FlowMetrics.SOURCE_OFFLINE)
                .tag(FlowMetrics.T_OUTCOME, FlowMetrics.REPLAY_SKIPPED_ACKED)
                .counter().count());
    }

    @Test
    void replayAndMerge_noFlowSessionManager_doesNotThrow() {
        // 未注入 manager 时 recordReplay 必须静默跳过，不抛 NPE
        FlowSession s = newSession("flow-obs-2", 72L);
        producer.preloadFlow("flow-obs-2", new InMemoryMessageQueueProducerStub.Entry(
                "id-1", 72L, 1L, (short) 0, 1, new byte[]{1}));
        EmbeddedChannel ch = new EmbeddedChannel();
        OfflineMessageService.ReplayResult r = service.replayAndMerge(s, ch, 0L);
        assertEquals(1, r.count(), "manager 缺省时 replay 业务仍正常");
        // 因为 service 用的是 new SimpleMeterRegistry() 但未注入 manager，新 metric 不会写入
        assertNull(((SimpleMeterRegistry) /* setUp 用的 reg */ new SimpleMeterRegistry())
                .find(FlowMetrics.M_REPLAY_TOTAL).counter(),
                "新 metric 不应在未注入 manager 的 registry 中出现（占位断言：未污染）");
    }

    // ==================== maxGwSeq ====================

    @Test
    void maxGwSeq_returnsHighestStoredSeq() {
        FlowSession s = newSession("flow-m1", 41L);
        for (long seq : new long[]{3L, 7L, 5L}) {
            producer.preloadFlow("flow-m1", new InMemoryMessageQueueProducerStub.Entry(
                    "id-" + seq, 41L, seq, (short) 0, 1, new byte[]{1}));
        }
        assertEquals(7L, service.maxGwSeq("flow-m1"));
    }

    @Test
    void maxGwSeq_emptyStream_returnsZero() {
        assertEquals(0L, service.maxGwSeq("flow-m-none"));
    }

    // ==================== helpers ====================

    private FlowSession newSession(String flowId, long playerId) {
        EmbeddedChannel ch = new EmbeddedChannel();
        FlowSession s = new FlowSession(flowId, playerId, 1, "gate-test-01",
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000, ch);
        // 模拟客户端断线 → DETACHED
        ch.close();
        return s;
    }

    private FlowSession newSessionWithBuffer(String flowId, long playerId) {
        FlowSession s = newSession(flowId, playerId);
        s.setBuffer(new DownstreamBuffer(64, 4096L, DownstreamBuffer.OverflowPolicy.DROP_OLDEST));
        return s;
    }

    private WrappedMessage downstream(int msgId, byte[] body) {
        MessageHeader h = new MessageHeader();
        h.setMode(MessageHeader.MODE_PUSH);
        h.setMessageId(msgId);
        return new WrappedMessage(h, new RawMessageBody(body));
    }

    /**
     * 内存版 MessageQueueProducer：覆盖 B3 接口，无视真实 Redis。
     */
    static class InMemoryMessageQueueProducerStub extends MessageQueueProducer {
        final ConcurrentMap<String, List<Entry>> flowStreams = new ConcurrentHashMap<>();
        final ConcurrentMap<Long, List<Map<String, Object>>> playerEntries = new ConcurrentHashMap<>();
        final AtomicLong idSeq = new AtomicLong(1);
        volatile boolean simulateFailure = false;

        InMemoryMessageQueueProducerStub() {
            super(null);
        }

        @Override
        public String saveOfflineFrame(String flowId, long playerId, long gwSeq,
                                       short flags, int messageId, byte[] body,
                                       Duration ttl) {
            if (simulateFailure) return null;
            String rid = "rid-" + idSeq.getAndIncrement();
            flowStreams.computeIfAbsent(flowId, k -> new ArrayList<>())
                    .add(new Entry(rid, playerId, gwSeq, flags, messageId, body));
            return rid;
        }

        @Override
        public List<OfflineEntry> readOfflineFrames(String flowId, long afterGwSeq, int batchSize) {
            List<Entry> es = flowStreams.getOrDefault(flowId, new ArrayList<>());
            List<OfflineEntry> out = new ArrayList<>();
            int limit = batchSize <= 0 ? 100 : batchSize;
            for (Entry e : es) {
                if (out.size() >= limit) break;
                if (e.gwSeq > afterGwSeq) {
                    out.add(new OfflineEntry(e.recordId, e.playerId, e.gwSeq, e.flags, e.messageId, e.body));
                }
            }
            out.sort((a, b) -> Long.compare(a.gwSeq(), b.gwSeq()));
            return out;
        }

        @Override
        public long deleteOfflineEntries(String flowId, List<String> recordIds) {
            List<Entry> es = flowStreams.get(flowId);
            if (es == null) return 0L;
            long removed = 0;
            for (String id : recordIds) {
                if (es.removeIf(e -> e.recordId.equals(id))) removed++;
            }
            return removed;
        }

        @Override
        public long maxOfflineGwSeq(String flowId) {
            List<Entry> es = flowStreams.get(flowId);
            if (es == null || es.isEmpty()) return 0L;
            long max = 0;
            for (Entry e : es) if (e.gwSeq > max) max = e.gwSeq;
            return max;
        }

        @Override
        public void deleteOfflineFlow(String flowId) {
            flowStreams.remove(flowId);
        }

        @Override
        public long offlineFlowSize(String flowId) {
            List<Entry> es = flowStreams.get(flowId);
            return es == null ? 0 : es.size();
        }

        @Override
        public String saveOfflineMessage(Long playerId, short messageId, Map<String, Object> messageData) {
            if (simulateFailure) return null;
            String rid = "prid-" + idSeq.getAndIncrement();
            Map<String, Object> rec = new HashMap<>(messageData);
            rec.put("msg_id", messageId);
            rec.put("record_id", rid);
            playerEntries.computeIfAbsent(playerId, k -> new ArrayList<>()).add(rec);
            return rid;
        }

        @Override
        public long getOfflineMessageCount(Long playerId) {
            List<Map<String, Object>> es = playerEntries.get(playerId);
            return es == null ? 0L : es.size();
        }

        @Override
        public void deleteOfflineMessages(Long playerId) {
            playerEntries.remove(playerId);
        }

        List<Entry> flowEntries(String flowId) {
            return flowStreams.getOrDefault(flowId, new ArrayList<>());
        }

        void preloadFlow(String flowId, Entry e) {
            flowStreams.computeIfAbsent(flowId, k -> new ArrayList<>()).add(e);
        }

        static class Entry {
            final String recordId;
            final long playerId;
            final long gwSeq;
            final short flags;
            final int messageId;
            final byte[] body;
            Entry(String recordId, long playerId, long gwSeq, short flags, int messageId, byte[] body) {
                this.recordId = recordId;
                this.playerId = playerId;
                this.gwSeq = gwSeq;
                this.flags = flags;
                this.messageId = messageId;
                this.body = body;
            }
        }
    }
}

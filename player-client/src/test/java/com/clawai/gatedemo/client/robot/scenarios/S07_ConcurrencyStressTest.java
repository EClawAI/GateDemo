package com.clawai.gatedemo.client.robot.scenarios;

import com.clawai.gatedemo.client.robot.framework.AbstractRobotScenario;
import com.clawai.gatedemo.client.robot.framework.LoginClient;
import com.clawai.gatedemo.client.robot.framework.RobotClient;
import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S07: 并发 robot 压测场景
 *
 * <p>N 个 robot 同时完成 login → AUTH → K 个 heartbeat-ack 往返；校验：
 * <ul>
 *   <li>所有 robot AUTH 成功，flowId 互不重复；</li>
 *   <li>HeartbeatAck 收发计数等于发送计数（计数允许等待 SLO 内）；</li>
 *   <li>整体时长在阈值内（用于发现 RESUME 路径阻塞或 lock 退化）。</li>
 * </ul>
 *
 * <p>规模可通过系统属性 {@code GATE_E2E_STRESS_N}（默认 10）与 {@code GATE_E2E_STRESS_K}（默认 5）
 * 调整，方便 nightly 跑更大压测。
 */
@DisplayName("S07: concurrent robot stress (N players × K heartbeats)")
public class S07_ConcurrencyStressTest extends AbstractRobotScenario {

    private static final long PLAYER_ID_BASE = 270000L;

    @Test
    @DisplayName("N 并发 robot 全部 AUTH 成功 + HeartbeatAck 收齐")
    public void concurrentLoginAndHeartbeat_allSucceed() throws Exception {
        int n = readInt("GATE_E2E_STRESS_N", 10);
        int k = readInt("GATE_E2E_STRESS_K", 5);
        long maxTotalMs = readLong("GATE_E2E_STRESS_MAX_MS", 30_000L);

        logger.info("[S07] N={} K={} maxTotalMs={}", n, k, maxTotalMs);

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 16));
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch start = new CountDownLatch(1);
            List<CompletableFuture<RobotOutcome>> futures = new ArrayList<>(n);

            for (int i = 0; i < n; i++) {
                final long playerId = PLAYER_ID_BASE + i;
                CompletableFuture<RobotOutcome> f = CompletableFuture.supplyAsync(() -> {
                    ready.countDown();
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return RobotOutcome.fail(playerId, "interrupted-before-start");
                    }
                    return runOne(playerId, k);
                }, pool);
                futures.add(f);
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "robot 线程未在 10s 内就绪");
            long t0 = System.nanoTime();
            start.countDown();

            // 等待全部完成（带总超时）
            CompletableFuture<Void> all = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture<?>[0]));
            all.get(maxTotalMs, TimeUnit.MILLISECONDS);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

            Set<String> flowIds = new HashSet<>();
            AtomicInteger okCount = new AtomicInteger();
            AtomicInteger ackCount = new AtomicInteger();
            List<String> failures = new ArrayList<>();
            for (CompletableFuture<RobotOutcome> f : futures) {
                RobotOutcome o = f.getNow(null);
                if (o == null) {
                    failures.add("future-null");
                    continue;
                }
                if (o.success) {
                    okCount.incrementAndGet();
                    ackCount.addAndGet(o.acksReceived);
                    assertTrue(flowIds.add(o.flowId),
                            "flowId 重复：playerId=" + o.playerId + " flowId=" + o.flowId);
                } else {
                    failures.add("player=" + o.playerId + " err=" + o.error);
                }
            }

            logger.info("[S07] elapsedMs={} ok={}/{} flowIds={} totalAcks={}/{}",
                    elapsedMs, okCount.get(), n, flowIds.size(), ackCount.get(), n * k);

            assertEquals(n, okCount.get(),
                    "AUTH 失败：" + String.join(" | ", failures));
            assertEquals(n, flowIds.size(), "flowId 必须互不重复，去重后剩 " + flowIds.size());
            assertEquals(n * k, ackCount.get(),
                    "HeartbeatAck 总数不符，预期 " + (n * k) + " 实际 " + ackCount.get());
        } finally {
            pool.shutdownNow();
        }
    }

    private RobotOutcome runOne(long playerId, int k) {
        try {
            LoginClient.LoginResult lr = login.login(playerId);
            try (RobotClient r = newRobot()) {
                AuthResponse resp = r.auth(lr.token(), lr.gameId());
                if (!resp.getSuccess()) {
                    return RobotOutcome.fail(playerId, "auth-failed: " + resp.getMessage());
                }
                assertNotNull(resp.getFlowId());

                int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
                int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
                int acks = 0;
                for (int j = 0; j < k; j++) {
                    r.send(hbId, ClientHeartbeat.newBuilder()
                            .setTimestamp(System.currentTimeMillis())
                            .setLastClientRecvSeq(r.lastClientRecvSeq())
                            .build().toByteArray());
                    if (r.awaitMessage(ackId, Duration.ofSeconds(5)).isPresent()) {
                        acks++;
                    } else {
                        return RobotOutcome.fail(playerId, "ack-timeout at " + j);
                    }
                }
                return RobotOutcome.ok(playerId, resp.getFlowId(), acks);
            }
        } catch (Exception e) {
            return RobotOutcome.fail(playerId, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private static int readInt(String key, int def) {
        String v = System.getProperty(key, System.getenv(key));
        if (v == null || v.isBlank()) return def;
        try { return Integer.parseInt(v); } catch (Exception ignored) { return def; }
    }

    private static long readLong(String key, long def) {
        String v = System.getProperty(key, System.getenv(key));
        if (v == null || v.isBlank()) return def;
        try { return Long.parseLong(v); } catch (Exception ignored) { return def; }
    }

    private static final class RobotOutcome {
        final long playerId;
        final boolean success;
        final String flowId;
        final int acksReceived;
        final String error;

        private RobotOutcome(long playerId, boolean success, String flowId, int acksReceived, String error) {
            this.playerId = playerId;
            this.success = success;
            this.flowId = flowId;
            this.acksReceived = acksReceived;
            this.error = error;
        }

        static RobotOutcome ok(long playerId, String flowId, int acks) {
            return new RobotOutcome(playerId, true, flowId, acks, null);
        }

        static RobotOutcome fail(long playerId, String error) {
            return new RobotOutcome(playerId, false, null, 0, error);
        }
    }
}

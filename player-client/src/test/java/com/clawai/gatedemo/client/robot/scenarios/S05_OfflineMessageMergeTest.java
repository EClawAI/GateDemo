package com.clawai.gatedemo.client.robot.scenarios;

import com.clawai.gatedemo.client.robot.framework.AbstractRobotScenario;
import com.clawai.gatedemo.client.robot.framework.LoginClient;
import com.clawai.gatedemo.client.robot.framework.RobotClient;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import com.clawai.gatedemo.proto.gate.ResumeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S05: 离线消息合流策略 smoke 场景
 *
 * <p>本场景验证 {@code gate.flow.offline.enabled=true} 配置下，DETACHED → 重新登录路径
 * 不会因 offline service 异常而阻塞主链路。完整合流语义（buffer flush → offline stream →
 * 下次 NEW drain）的端到端断言需要 server 主动 push 业务帧，当前 demo 缺乏该路径，
 * 因此本场景只做 smoke：
 *
 * <ol>
 *   <li>{@link #resumeAfterDetach_offlinePathSmoke}：NEW → heartbeat → abruptClose（进入
 *       DETACHED）→ RESUME 同 flowId，验证 RESUMED 不被 offline 路径阻塞，且 lastSeqAnchor
 *       的恢复路径不打断业务收发。</li>
 *   <li>{@link #newLoginAfterCrossEvict_offlineSwitchDoesNotBlock}：单 player 两次 NEW
 *       触发 cross-evict（flushOnCrossEvict 配置开启时会走 offline 写入路径），验证第二次
 *       NEW 成功且新 channel 后续可正常收发心跳。</li>
 * </ol>
 *
 * <p>更完整的 offline merge 语义（包括 drain count 与 offline stream 长度断言）将
 * 留到 P2，待 game-service 暴露 server-pushed message 后再补。
 */
@DisplayName("S05: offline message merge smoke")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class S05_OfflineMessageMergeTest extends AbstractRobotScenario {

    private static final long PLAYER_ID_BASE = 240000L;

    @Test
    @Order(1)
    @DisplayName("NEW → heartbeats → abruptClose → RESUME 不被 offline 路径阻塞")
    public void resumeAfterDetach_offlinePathSmoke() throws Exception {
        long playerId = PLAYER_ID_BASE + 1;
        LoginClient.LoginResult lr = login.login(playerId);

        String flowId;
        long ackBefore;
        try (RobotClient r1 = newRobot()) {
            AuthResponse resp = r1.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess(), "AUTH 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            flowId = resp.getFlowId();
            assertNotNull(flowId);

            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            for (int i = 0; i < 3; i++) {
                ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                        .setTimestamp(System.currentTimeMillis())
                        .setLastClientRecvSeq(0L)
                        .build();
                r1.send(hbId, hb.toByteArray());
                Optional<WrappedMessage> ack = r1.awaitMessage(ackId, Duration.ofSeconds(3));
                assertTrue(ack.isPresent(), "HeartbeatAck #" + i + " 缺失");
            }
            ackBefore = r1.lastClientRecvSeq();
            r1.abruptClose();
        }

        LoginClient.LoginResult lr2 = login.login(playerId);
        try (RobotClient r2 = newRobot()) {
            long t0 = System.nanoTime();
            AuthResponse resp = r2.authResume(lr2.token(), lr2.gameId(), flowId, ackBefore);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(resp.getSuccess(), "RESUME 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.RESUMED, resp.getResumeStatus());
            assertEquals(flowId, resp.getFlowId());
            // 验证 offline 路径不引入显著阻塞（< 1.5s，避免出现 server-side 阻塞性 bug）
            assertTrue(elapsedMs < 1500,
                    "RESUME 耗时 " + elapsedMs + " ms 超过 1.5s，offline 路径可能阻塞主线程");
            logger.info("[S05#1] resume after detach ok in {} ms", elapsedMs);

            // RESUME 后业务收发应正常
            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            r2.send(hbId, ClientHeartbeat.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setLastClientRecvSeq(r2.lastClientRecvSeq())
                    .build().toByteArray());
            assertTrue(r2.awaitMessage(ackId, Duration.ofSeconds(3)).isPresent(),
                    "RESUME 后心跳应继续可达");
        }
    }

    @Test
    @Order(2)
    @DisplayName("两次 NEW 触发 cross-evict 时 offline switch 不阻塞主链路")
    public void newLoginAfterCrossEvict_offlineSwitchDoesNotBlock() throws Exception {
        long playerId = PLAYER_ID_BASE + 2;
        LoginClient.LoginResult lr = login.login(playerId);

        String firstFlowId;
        try (RobotClient r1 = newRobot()) {
            AuthResponse resp = r1.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess());
            firstFlowId = resp.getFlowId();
            // 发若干 heartbeat 让 buffer 中有 entry，flushOnCrossEvict 时会走 offline 写入路径
            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            for (int i = 0; i < 3; i++) {
                r1.send(hbId, ClientHeartbeat.newBuilder()
                        .setTimestamp(System.currentTimeMillis())
                        .setLastClientRecvSeq(0L)
                        .build().toByteArray());
            }
            // 不 drain HeartbeatAck，让 buffer 持有未 ACK 帧
            sleepQuiet(Duration.ofMillis(200));
        }

        LoginClient.LoginResult lr2 = login.login(playerId);
        try (RobotClient r2 = newRobot()) {
            long t0 = System.nanoTime();
            AuthResponse resp = r2.auth(lr2.token(), lr2.gameId());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            assertTrue(resp.getSuccess(), "第二次 NEW 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            assertNotEquals(firstFlowId, resp.getFlowId(),
                    "顶号后必须 mint 新 flowId");
            assertTrue(elapsedMs < 1500,
                    "第二次 NEW 耗时 " + elapsedMs + " ms 超过 1.5s，offline flush 可能阻塞主线程");
            logger.info("[S05#2] new-after-evict ok in {} ms; new flowId={}", elapsedMs, resp.getFlowId());

            // 新会话业务收发正常
            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            r2.send(hbId, ClientHeartbeat.newBuilder()
                    .setTimestamp(System.currentTimeMillis())
                    .setLastClientRecvSeq(0L)
                    .build().toByteArray());
            assertTrue(r2.awaitMessage(ackId, Duration.ofSeconds(3)).isPresent());
        }
    }
}

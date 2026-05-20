package com.clawai.gatedemo.client.robot.scenarios;

import com.clawai.gatedemo.client.robot.framework.AbstractRobotScenario;
import com.clawai.gatedemo.client.robot.framework.LoginClient;
import com.clawai.gatedemo.client.robot.framework.RobotClient;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ResumeStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S02: FlowSession 生命周期场景
 *
 * <p>覆盖核心状态机：NEW → ATTACHED → DETACHED → RESUMED；
 * 以及 NEW 顶号、伪造 flowId 的 REJECTED_EXPIRED 降级路径。
 *
 * <p>校验点：
 * <ol>
 *   <li>{@link #newThenResume_sameFlowId}：单 player NEW 后立即 close + RESUME，flowId 必须保持，
 *       resume_status = RESUMED；</li>
 *   <li>{@link #newAfterNew_oldChannelEvicted}：同 player 第二次走 NEW（不带 flowId），第一个 robot
 *       的 channel 应被服务端顶号关闭；</li>
 *   <li>{@link #resumeWithFakeFlowId_degradesToNew}：使用一个不存在的 UUID 做 RESUME，期望
 *       resume_status = REJECTED_EXPIRED 且返回新的 flowId（服务端已降级 NEW）。</li>
 * </ol>
 *
 * <p>本场景不验证「DETACHED TTL 真正过期后 RESUME 被拒」——该路径耗时强依赖
 * {@code gate.flow.detached-ttl-seconds}（默认 60s，CI 跑不起），用专门的 P2 长跑测试覆盖。
 */
@DisplayName("S02: FlowSession lifecycle (NEW / RESUME / takeover / fake-id reject)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class S02_FlowSessionLifecycleTest extends AbstractRobotScenario {

    private static final long PLAYER_ID_BASE = 210000L;

    @Test
    @Order(1)
    @DisplayName("NEW → close → RESUME 同 flowId 必须保持")
    public void newThenResume_sameFlowId() throws Exception {
        long playerId = PLAYER_ID_BASE + 1;
        LoginClient.LoginResult lr = login.login(playerId);

        String firstFlowId;
        long ackBefore;
        try (RobotClient r1 = newRobot()) {
            AuthResponse resp = r1.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess(), "首次 AUTH 必须成功：" + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            firstFlowId = resp.getFlowId();
            assertNotNull(firstFlowId);
            assertTrue(!firstFlowId.isBlank());
            ackBefore = r1.lastClientRecvSeq();
            logger.info("[S02#1] first flowId={}, lastRecv={}", firstFlowId, ackBefore);
        }

        // 重新登录拿 fresh token 后用同 flowId 走 RESUME 路径
        LoginClient.LoginResult lr2 = login.login(playerId);
        try (RobotClient r2 = newRobot()) {
            AuthResponse resp = r2.authResume(lr2.token(), lr2.gameId(), firstFlowId, ackBefore);
            assertTrue(resp.getSuccess(), "RESUME 必须成功：" + resp.getMessage());
            assertEquals(ResumeStatus.RESUMED, resp.getResumeStatus(),
                    "expected RESUMED, got " + resp.getResumeStatus());
            assertEquals(firstFlowId, resp.getFlowId(),
                    "RESUMED 路径必须返回相同 flowId");
            logger.info("[S02#1] resumed ok; flowId stable");
        }
    }

    @Test
    @Order(2)
    @DisplayName("同 player 第二次 NEW（不带 flowId）→ 第一个 robot 必须被顶号关闭")
    public void newAfterNew_oldChannelEvicted() throws Exception {
        long playerId = PLAYER_ID_BASE + 2;
        LoginClient.LoginResult lr = login.login(playerId);

        RobotClient r1 = newRobot();
        AuthResponse resp1 = r1.auth(lr.token(), lr.gameId());
        assertTrue(resp1.getSuccess());
        assertEquals(ResumeStatus.NEW, resp1.getResumeStatus());
        String flowOld = resp1.getFlowId();

        // 第二个 robot 用 fresh token + 不带 flowId，触发服务端 NEW 顶号
        LoginClient.LoginResult lr2 = login.login(playerId);
        try (RobotClient r2 = newRobot()) {
            AuthResponse resp2 = r2.auth(lr2.token(), lr2.gameId());
            assertTrue(resp2.getSuccess());
            assertEquals(ResumeStatus.NEW, resp2.getResumeStatus());
            assertNotEquals(flowOld, resp2.getFlowId(),
                    "NEW 顶号后必须 mint 新 flowId");
            logger.info("[S02#2] new flowId minted; old={} new={}", flowOld, resp2.getFlowId());

            // 给服务端一个写宽限：close 旧 channel 是异步的
            assertTrue(r1.awaitInactive(Duration.ofSeconds(5)),
                    "旧 robot 的 channel 应被服务端顶号关闭，但仍 active");
        }
    }

    @Test
    @Order(3)
    @DisplayName("伪造 flowId 走 RESUME → REJECTED_EXPIRED 降级 NEW")
    public void resumeWithFakeFlowId_degradesToNew() throws Exception {
        long playerId = PLAYER_ID_BASE + 3;
        LoginClient.LoginResult lr = login.login(playerId);

        String fakeFlowId = UUID.randomUUID().toString();
        try (RobotClient r = newRobot()) {
            AuthResponse resp = r.authResume(lr.token(), lr.gameId(), fakeFlowId, 0L);
            assertTrue(resp.getSuccess(),
                    "REJECTED_EXPIRED 应降级 NEW 而非整体失败，message=" + resp.getMessage());
            assertEquals(ResumeStatus.REJECTED_EXPIRED, resp.getResumeStatus());
            assertNotNull(resp.getFlowId());
            assertTrue(!resp.getFlowId().isBlank(),
                    "降级 NEW 必须返回非空 flowId");
            assertNotEquals(fakeFlowId, resp.getFlowId(),
                    "降级 NEW 必须 mint 不同的新 flowId");
            logger.info("[S02#3] fake={}, minted={}", fakeFlowId, resp.getFlowId());
        }
    }
}

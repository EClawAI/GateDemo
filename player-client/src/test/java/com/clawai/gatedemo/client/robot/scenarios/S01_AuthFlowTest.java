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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S01: 全链路认证场景
 *
 * <p>路径：login-service {@code /api/v1/login} (HTTP) → gate-service {@code /ws} (WebSocket)
 * → {@code AuthRequest} / {@code AuthResponse}
 *
 * <p>校验点：
 * <ul>
 *   <li>登录服务能成功签发 JWT 与目标 gate 信息；</li>
 *   <li>WebSocket 握手在超时内完成；</li>
 *   <li>{@code AuthResponse.success = true}，下发 {@code flowId} 非空；</li>
 *   <li>首次 AUTH 走 NEW 路径（{@code resume_status = NEW}）；</li>
 *   <li>{@code server_features} 协商成功（与客户端 features 取交集 != 0）。</li>
 * </ul>
 *
 * <p>此场景是 e2e pipeline 的 smoke test：跑通即说明服务栈整体可用。
 */
@DisplayName("S01: Auth Flow end-to-end")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class S01_AuthFlowTest extends AbstractRobotScenario {

    private static final long PLAYER_ID = 200001L;

    @Test
    @Order(1)
    @DisplayName("login → gate AUTH → AuthResponse.success=true 且 flowId 非空")
    public void newFlowAuth_succeeds() throws Exception {
        LoginClient.LoginResult lr = login.login(PLAYER_ID);
        assertNotNull(lr.token(), "login 必须返回 token");
        assertTrue(lr.gameId() > 0, "login 必须返回 gameId");
        assertTrue(lr.gatePort() > 0, "login 必须返回 gatePort");
        logger.info("[S01] login ok: gameId={}, gate={}:{}, tokenLen={}",
                lr.gameId(), lr.gateHost(), lr.gatePort(), lr.token().length());

        try (RobotClient robot = newRobot()) {
            long t0 = System.nanoTime();
            AuthResponse resp = robot.auth(lr.token(), lr.gameId());
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
            logger.info("[S01] AuthResponse received in {} ms: success={}, flowId={}, status={}, serverFeatures=0x{}",
                    elapsedMs, resp.getSuccess(), resp.getFlowId(),
                    resp.getResumeStatus(),
                    Integer.toHexString(resp.getServerFeatures()));

            assertTrue(resp.getSuccess(), "auth 必须成功；服务端 message=" + resp.getMessage());
            assertTrue(resp.getFlowId() != null && !resp.getFlowId().isBlank(),
                    "AuthResponse 必须下发非空 flowId");
            assertEquals(PLAYER_ID, resp.getPlayerId(),
                    "AuthResponse.playerId 必须等于登录玩家 ID");
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus(),
                    "首次 AUTH（无 flowId）期望 ResumeStatus.NEW");

            assertTrue(resp.getServerFeatures() != 0,
                    "server_features 协商失败：响应中为 0");
        }
    }

    @Test
    @Order(2)
    @DisplayName("不带 token 时 AUTH 失败（success=false 或连接被拒）")
    public void authWithoutToken_fails() throws Exception {
        try (RobotClient robot = newRobot()) {
            try {
                AuthResponse resp = robot.auth("", 1001);
                assertTrue(!resp.getSuccess(),
                        "空 token 期望 success=false，实际 success=true");
            } catch (Exception ex) {
                // 服务端可能直接断链，导致 expect future 异常，这也是允许的失败形态
                logger.info("[S01] empty token AUTH failed as expected: {}", ex.getClass().getSimpleName());
            }
        }
    }
}

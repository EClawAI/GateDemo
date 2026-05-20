package com.clawai.gatedemo.client.robot.scenarios;

import com.clawai.gatedemo.client.robot.framework.AbstractRobotScenario;
import com.clawai.gatedemo.client.robot.framework.LoginClient;
import com.clawai.gatedemo.client.robot.framework.RobotClient;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ResumeStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S04: 跨实例 owner takeover 场景
 *
 * <p>路径：
 * <ol>
 *   <li>player 连 gate-01 完成 NEW AUTH，拿到 flowId；</li>
 *   <li>player 用同 flowId + 同 token 直接连 gate-02 走 RESUME（绕开 login 路由）；</li>
 *   <li>gate-02 通过 Redis Lua {@code flow_cross_takeover.lua} 原子换 owner，返回 RESUMED；</li>
 *   <li>gate-01 的旧 channel 应被 Pub/Sub evict 通知关闭；客户端收到 channelInactive。</li>
 * </ol>
 *
 * <p>前置：docker-compose 的 gate-01 (8888) 与 gate-02 (8889) 都已启动且共享 Redis。
 * 若 8889 不可达则跳过本场景（assumeTrue），避免在只跑 gate-01 的开发机上误红。
 */
@DisplayName("S04: cross-instance owner takeover (gate-01 → gate-02)")
public class S04_CrossInstanceTakeoverTest extends AbstractRobotScenario {

    private static final long PLAYER_ID = 230001L;

    @Test
    @DisplayName("gate-01 NEW → gate-02 RESUME → 旧 channel 被 evict")
    public void resumeOnSecondaryGate_evictsOriginalOwner() throws Exception {
        Assumptions.assumeTrue(isReachable(env.host(), env.wsPort2()),
                "secondary gate ws port " + env.wsPort2() + " 不可达，跳过 S04");

        LoginClient.LoginResult lr = login.login(PLAYER_ID);

        String flowId;
        long ackBefore;
        RobotClient r1 = newRobotOnWs(env.wsPort());
        try {
            AuthResponse resp1 = r1.auth(lr.token(), lr.gameId());
            assertTrue(resp1.getSuccess(), "gate-01 AUTH 失败: " + resp1.getMessage());
            assertEquals(ResumeStatus.NEW, resp1.getResumeStatus());
            flowId = resp1.getFlowId();
            ackBefore = r1.lastClientRecvSeq();
            assertNotNull(flowId);
            logger.info("[S04] gate-01 flowId={}", flowId);

            // 不立刻 close r1：让 RESUME 必须靠跨实例 evict 才能完成换绑
            // 直接连 gate-02 走 RESUME
            LoginClient.LoginResult lr2 = login.login(PLAYER_ID);
            try (RobotClient r2 = newRobotOnWs(env.wsPort2())) {
                AuthResponse resp2 = r2.authResume(lr2.token(), lr2.gameId(), flowId, ackBefore);
                assertTrue(resp2.getSuccess(), "gate-02 RESUME 失败: " + resp2.getMessage());
                assertEquals(ResumeStatus.RESUMED, resp2.getResumeStatus(),
                        "expected RESUMED on gate-02, got " + resp2.getResumeStatus()
                                + ", message=" + resp2.getMessage());
                assertEquals(flowId, resp2.getFlowId(),
                        "RESUMED 后 flowId 必须保持");
                logger.info("[S04] gate-02 RESUMED ok; expecting r1 channel to be evicted");

                // gate-01 的 r1 应被 Pub/Sub 通知 evict 后关闭：给 5s 宽限
                boolean inactive = r1.awaitInactive(Duration.ofSeconds(5));
                assertTrue(inactive, "原 gate-01 channel 应在 5s 内被 evict 关闭，但仍 active");
            }
        } finally {
            try { r1.close(); } catch (Exception ignored) {}
        }
    }

    private static boolean isReachable(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

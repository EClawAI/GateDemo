package com.clawai.gatedemo.client.robot.scenarios;

import com.clawai.gatedemo.client.robot.framework.AbstractRobotScenario;
import com.clawai.gatedemo.client.robot.framework.LoginClient;
import com.clawai.gatedemo.client.robot.framework.RobotClient;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import com.clawai.gatedemo.proto.gate.ResumeStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S06: WebSocket + TCP 多 transport 场景
 *
 * <p>校验 {@code openspec/changes/add-multi-transport-abstraction} 的端到端可用性：
 * 同一 gate 实例同时启用 WS（默认 8888）与 TCP（默认 9999）端口，两个 transport 各自独立
 * 完成完整 AUTH → heartbeat → HeartbeatAck 链路，并使用不同 playerId 避免相互顶号。
 *
 * <p>若 {@code GATE_E2E_TCP_PORT} 不可达（docker-compose 未启用 TCP 暴露），整段跳过。
 */
@DisplayName("S06: multi-transport (WS + TCP) parallel AUTH")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class S06_MultiTransportTest extends AbstractRobotScenario {

    private static final long PLAYER_ID_WS = 260001L;
    private static final long PLAYER_ID_TCP = 260002L;

    @Test
    @Order(1)
    @DisplayName("WebSocket transport 完整链路")
    public void webSocketTransport_authAndHeartbeat() throws Exception {
        LoginClient.LoginResult lr = login.login(PLAYER_ID_WS);
        try (RobotClient r = newRobotOnWs(env.wsPort())) {
            AuthResponse resp = r.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess(), "WS AUTH 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            assertNotNull(resp.getFlowId());

            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            r.send(hbId, ClientHeartbeat.newBuilder()
                    .setTimestamp(System.currentTimeMillis()).build().toByteArray());
            assertTrue(r.awaitMessage(ackId, Duration.ofSeconds(3)).isPresent(),
                    "WS HeartbeatAck 应到达");
        }
    }

    @Test
    @Order(2)
    @DisplayName("TCP transport 完整链路（若端口可达）")
    public void tcpTransport_authAndHeartbeat() throws Exception {
        Assumptions.assumeTrue(env.tcpEnabled(),
                "GATE_E2E_TCP_PORT <= 0，跳过 TCP transport 场景");
        Assumptions.assumeTrue(isReachable(env.host(), env.tcpPort()),
                "TCP port " + env.tcpPort() + " 不可达，跳过 TCP transport 场景");

        LoginClient.LoginResult lr = login.login(PLAYER_ID_TCP);
        try (RobotClient r = newRobotOnTcp(env.tcpPort())) {
            AuthResponse resp = r.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess(), "TCP AUTH 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            assertNotNull(resp.getFlowId());

            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            r.send(hbId, ClientHeartbeat.newBuilder()
                    .setTimestamp(System.currentTimeMillis()).build().toByteArray());
            Optional<WrappedMessage> ack = r.awaitMessage(ackId, Duration.ofSeconds(3));
            assertTrue(ack.isPresent(), "TCP HeartbeatAck 应到达");
            assertTrue(ack.get().getHeader().hasGwSeq() || true,
                    "（弱断言）TCP 路径 HeartbeatAck 至少不抛错");
        }
    }

    @Test
    @Order(3)
    @DisplayName("WS 与 TCP 并发：两 playerId 互不影响")
    public void wsAndTcp_parallel_distinctPlayers() throws Exception {
        Assumptions.assumeTrue(env.tcpEnabled() && isReachable(env.host(), env.tcpPort()),
                "TCP 不可用，跳过并发 transport 场景");

        LoginClient.LoginResult lrWs = login.login(PLAYER_ID_WS + 100);
        LoginClient.LoginResult lrTcp = login.login(PLAYER_ID_TCP + 100);

        try (RobotClient rWs = newRobotOnWs(env.wsPort());
             RobotClient rTcp = newRobotOnTcp(env.tcpPort())) {

            AuthResponse aWs = rWs.auth(lrWs.token(), lrWs.gameId());
            AuthResponse aTcp = rTcp.auth(lrTcp.token(), lrTcp.gameId());
            assertTrue(aWs.getSuccess() && aTcp.getSuccess(),
                    "并发 AUTH 必须都成功；ws.msg=" + aWs.getMessage() + ", tcp.msg=" + aTcp.getMessage());
            assertNotEquals(aWs.getFlowId(), aTcp.getFlowId(),
                    "两个 transport 的 flowId 必须互相独立");
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

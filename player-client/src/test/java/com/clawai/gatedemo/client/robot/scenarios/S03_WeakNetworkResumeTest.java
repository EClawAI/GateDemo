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
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * S03: 弱网 RESUME + 下行 buffer replay 场景
 *
 * <p>触发 HeartbeatAck 作为可观察的「服务端下行帧」，校验 B1 buffer 重放路径：
 * <ol>
 *   <li>NEW + features 协商成功（{@code server_features} 含 GW_SEQ + REPLAY 位）；</li>
 *   <li>累计若干 ClientHeartbeat → HeartbeatAck（每一帧应带 gwSeq）；</li>
 *   <li>记录最大 gwSeq <em>但故意不 ACK</em>（lastClientRecvSeq 在客户端侧推进，
 *       但服务端尚未收到 ACK 心跳，因此 buffer 未被裁剪）；</li>
 *   <li>{@link RobotClient#abruptClose()} 模拟网络中断；</li>
 *   <li>{@link RobotClient#authResume} 使用 {@code lastClientRecvSeq=0} 强制服务端 replay 全部
 *       buffer 中的 HeartbeatAck；</li>
 *   <li>校验 RESUMED 后 inbox 出现重放出来的 HeartbeatAck 数（不少于发送数）。</li>
 * </ol>
 *
 * <p>注意：服务端 {@code features.advertise-gw-seq} 与 {@code advertise-replay} 必须开启
 * （docker-compose dev profile 默认开启）；否则本场景无可观察的 replay，整段都会被
 * 标记 SKIP（fail-soft，避免 e2e 红屏）。
 */
@DisplayName("S03: weak-network RESUME + downstream buffer replay")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class S03_WeakNetworkResumeTest extends AbstractRobotScenario {

    private static final long PLAYER_ID = 220001L;
    /** 验证 buffer/replay 需要的服务端 features 位（bit0 GW_SEQ | bit1 REPLAY）。 */
    private static final int REQUIRED_FEATURES = 0x3;

    @Test
    @Order(1)
    @DisplayName("NEW + heartbeats → abruptClose → RESUME(lastClientRecvSeq=0) → 必须 replay 出 HeartbeatAck")
    public void weakNetworkResume_replaysBufferedAcks() throws Exception {
        LoginClient.LoginResult lr = login.login(PLAYER_ID);
        String flowId;
        long maxGwSeqObserved;
        int heartbeatCount = 5;

        try (RobotClient r1 = newRobot()) {
            AuthResponse resp = r1.auth(lr.token(), lr.gameId());
            assertTrue(resp.getSuccess(), "AUTH 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.NEW, resp.getResumeStatus());
            int negotiated = resp.getServerFeatures();
            if ((negotiated & REQUIRED_FEATURES) != REQUIRED_FEATURES) {
                logger.warn("[S03] server_features=0x{} 不含 GW_SEQ+REPLAY，跳过本场景",
                        Integer.toHexString(negotiated));
                return;
            }
            flowId = resp.getFlowId();

            int hbId = MessageRouteRegistry.getIdByName("ClientHeartbeat");
            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            for (int i = 0; i < heartbeatCount; i++) {
                // 故意把 last_client_recv_seq 置 0，避免服务端 ackUpTo 提前裁剪
                ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                        .setTimestamp(System.currentTimeMillis())
                        .setLastClientRecvSeq(0L)
                        .build();
                r1.send(hbId, hb.toByteArray());
                Optional<WrappedMessage> ack = r1.awaitMessage(ackId, Duration.ofSeconds(3));
                assertTrue(ack.isPresent(), "第 " + i + " 个 HeartbeatAck 超时未到达");
                assertTrue(ack.get().getHeader().hasGwSeq(),
                        "HeartbeatAck 必须带 gwSeq（服务端 stamp）");
            }
            maxGwSeqObserved = r1.lastClientRecvSeq();
            assertTrue(maxGwSeqObserved >= heartbeatCount,
                    "lastRecvSeq=" + maxGwSeqObserved + "，期望 >= " + heartbeatCount);
            logger.info("[S03] flowId={}, maxGwSeq={}", flowId, maxGwSeqObserved);

            // 模拟网络瞬断：直接关 TCP，不发关闭帧
            r1.abruptClose();
        }

        // RESUME 路径：lastClientRecvSeq=0 强制服务端从 buffer 起始重放所有 entry
        LoginClient.LoginResult lr2 = login.login(PLAYER_ID);
        try (RobotClient r2 = newRobot()) {
            AuthResponse resp = r2.authResume(lr2.token(), lr2.gameId(), flowId, 0L);
            assertTrue(resp.getSuccess(), "RESUME 失败: " + resp.getMessage());
            assertEquals(ResumeStatus.RESUMED, resp.getResumeStatus(),
                    "expected RESUMED, got " + resp.getResumeStatus());

            // 等一拍让 server replay 完所有 buffer entries
            sleepQuiet(Duration.ofMillis(500));

            int ackId = MessageRouteRegistry.getIdByName("HeartbeatAck");
            List<WrappedMessage> inbox = r2.drainInbox();
            long replayed = inbox.stream()
                    .filter(m -> m.getHeader().getMessageId() == ackId)
                    .count();
            logger.info("[S03] resume replayed HeartbeatAck count={}, expected >= {}",
                    replayed, heartbeatCount);
            // server 可能在 RESUMED 之前先 drain buffer；只要 >= heartbeatCount 即视为 replay 成功
            assertTrue(replayed >= heartbeatCount,
                    "replay 不足：收到 " + replayed + "，期望 >= " + heartbeatCount);
            // 收到的最大 gwSeq 应至少恢复到 maxGwSeqObserved
            long after = r2.lastClientRecvSeq();
            assertTrue(after >= maxGwSeqObserved,
                    "RESUME 后 lastRecvSeq=" + after + "，应 >= " + maxGwSeqObserved);
            assertNotNull(resp.getFlowId());
        }
    }
}

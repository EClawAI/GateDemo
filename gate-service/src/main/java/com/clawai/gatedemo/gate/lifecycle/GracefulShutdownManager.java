package com.clawai.gatedemo.gate.lifecycle;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.gate.ws.NettyWebSocketServer;
import com.clawai.gatedemo.proto.gate.ServerShutdownNotice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 优雅关闭编排器：停止接受新连接 → 通知玩家 → 等待 drain → 强制关闭。
 */
@Component
@DependsOn("nettyWebSocketServer")
public class GracefulShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private static final int MSG_ID_SHUTDOWN = MessageRouteRegistry.getIdByName("ServerShutdownNotice");

    private final NettyWebSocketServer nettyWebSocketServer;
    private final PlayerService playerService;

    @Value("${gate.shutdown.drain-timeout-seconds:30}")
    private int drainTimeoutSeconds;

    @Value("${gate.shutdown.enabled:true}")
    private boolean enabled;

    public GracefulShutdownManager(NettyWebSocketServer nettyWebSocketServer, PlayerService playerService) {
        this.nettyWebSocketServer = nettyWebSocketServer;
        this.playerService = playerService;
    }

    @PreDestroy
    public void onShutdown() {
        if (!enabled) {
            logger.info("优雅关闭已禁用，跳过编排");
            return;
        }

        logger.info("=== 开始优雅关闭，drain 超时 {} 秒 ===", drainTimeoutSeconds);

        nettyWebSocketServer.stopAccepting();
        logger.info("阶段一完成：已停止接受新连接");

        Set<Long> playerIds = playerService.getAllOnlinePlayerIds();
        int count = playerIds.size();

        if (count > 0) {
            logger.info("阶段二：通知 {} 个玩家服务器即将关闭", count);

            ServerShutdownNotice notice = ServerShutdownNotice.newBuilder()
                    .setReason("Server is shutting down, please reconnect later").build();

            WrappedMessage shutdownMsg = new WrappedMessage();
            shutdownMsg.getHeader().setMessageId(MSG_ID_SHUTDOWN);
            shutdownMsg.getHeader().setMode(MessageHeader.MODE_PUSH);
            shutdownMsg.setBody(new RawMessageBody(notice.toByteArray()));

            for (Long playerId : playerIds) {
                playerService.sendToPlayer(playerId, shutdownMsg);
            }

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(drainTimeoutSeconds);
            while (playerService.getOnlineCount() > 0 && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("优雅关闭等待被中断");
                    break;
                }
            }

            int remaining = playerService.getOnlineCount();
            if (remaining > 0) {
                logger.warn("drain 超时，仍有 {} 个连接未断开，将由 Netty 强制关闭", remaining);
            } else {
                logger.info("阶段二完成：所有玩家已断开");
            }
        } else {
            logger.info("阶段二：无活跃玩家，跳过 drain");
        }

        logger.info("=== 优雅关闭编排完成 ===");
    }
}

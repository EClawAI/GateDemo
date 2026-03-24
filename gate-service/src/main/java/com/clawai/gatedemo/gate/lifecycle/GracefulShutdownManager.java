package com.clawai.gatedemo.gate.lifecycle;

import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import com.clawai.gatedemo.gate.ws.NettyWebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 优雅关闭编排器
 * <p>
 * 在 JVM 关闭时按阶段执行：
 * 1. 停止接受新连接（关闭 Netty server channel）
 * 2. 通知已连接玩家服务器即将关闭
 * 3. 等待连接 drain，最长 drain-timeout 秒
 * 4. 超时后由 NettyWebSocketServer 的 stop() 强制关闭剩余连接
 */
@Component
@DependsOn("nettyWebSocketServer")
public class GracefulShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);

    private final NettyWebSocketServer nettyWebSocketServer;
    private final PlayerService playerService;

    @Value("${gate.shutdown.drain-timeout-seconds:30}")
    private int drainTimeoutSeconds;

    @Value("${gate.shutdown.enabled:true}")
    private boolean enabled;

    /**
     * @param nettyWebSocketServer 用于停止 accept 与后续 Netty 关闭
     * @param playerService        广播关服通知并轮询在线数
     */
    public GracefulShutdownManager(NettyWebSocketServer nettyWebSocketServer, PlayerService playerService) {
        this.nettyWebSocketServer = nettyWebSocketServer;
        this.playerService = playerService;
    }

    /**
     * 容器销毁前执行：停接入、通知玩家、等待 drain；禁用或中断时提前返回。
     */
    @PreDestroy
    public void onShutdown() {
        if (!enabled) {
            logger.info("优雅关闭已禁用，跳过编排");
            return;
        }

        logger.info("=== 开始优雅关闭，drain 超时 {} 秒 ===", drainTimeoutSeconds);

        // 阶段一：停止接受新连接
        nettyWebSocketServer.stopAccepting();
        logger.info("阶段一完成：已停止接受新连接");

        // 阶段二：通知玩家并等待 drain
        Set<Long> playerIds = playerService.getAllOnlinePlayerIds();
        int count = playerIds.size();

        if (count > 0) {
            logger.info("阶段二：通知 {} 个玩家服务器即将关闭", count);

            PlayerMessage shutdownNotice = new PlayerMessage();
            shutdownNotice.setType("server_shutdown");
            shutdownNotice.setTimestamp(System.currentTimeMillis());
            shutdownNotice.setBody(java.util.Map.of("reason", "Server is shutting down, please reconnect later"));

            for (Long playerId : playerIds) {
                playerService.sendToPlayer(playerId, shutdownNotice);
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

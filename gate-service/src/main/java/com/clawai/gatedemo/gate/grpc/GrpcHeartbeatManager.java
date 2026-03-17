package com.clawai.gatedemo.gate.grpc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * gRPC心跳管理器
 * 
 * 负责定时发送心跳消息到Game服务
 * 保持Stream连接活跃
 */
@Component
public class GrpcHeartbeatManager {

    private static final Logger logger = LoggerFactory.getLogger(GrpcHeartbeatManager.class);

    private final GameGrpcClientPool clientPool;

    public GrpcHeartbeatManager(GameGrpcClientPool clientPool) {
        this.clientPool = clientPool;
    }

    @Scheduled(fixedDelayString = "${gate.grpc-pool.heartbeat-interval:30000}")
    public void sendHeartbeats() {
        Set<Integer> gameIds = clientPool.getGameIds();
        if (gameIds.isEmpty()) {
            return;
        }

        logger.debug("💓 发送gRPC心跳到所有Game服务，连接数：{}", gameIds.size());
        
        for (int gameId : gameIds) {
            try {
                clientPool.sendHeartbeat(gameId);
            } catch (Exception e) {
                logger.warn("⚠️ 发送心跳到Game {} 失败：{}", gameId, e.getMessage());
            }
        }
    }
}

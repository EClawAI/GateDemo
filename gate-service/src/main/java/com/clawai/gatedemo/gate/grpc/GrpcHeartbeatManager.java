package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

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
    private final GateConfig gateConfig;

    public GrpcHeartbeatManager(GameGrpcClientPool clientPool, GateConfig gateConfig) {
        this.clientPool = clientPool;
        this.gateConfig = gateConfig;
    }

    /**
     * 定时发送心跳
     * 
     * 默认每30秒发送一次心跳
     * 保活Stream连接
     */
    @Scheduled(fixedDelayString = "${gate.grpc.heartbeat-interval:30000}")
    public void sendHeartbeats() {
        int poolSize = clientPool.getPoolSize();
        if (poolSize == 0) {
            return;
        }

        logger.debug("💓 发送gRPC心跳到所有Game服务，连接数：{}", poolSize);
        
        // 遍历所有Game服务发送心跳
        for (int gameId : getGameIds()) {
            try {
                clientPool.sendHeartbeat(gameId);
            } catch (Exception e) {
                logger.warn("⚠️ 发送心跳到Game {} 失败：{}", gameId, e.getMessage());
            }
        }
    }

    /**
     * 获取所有Game服务ID
     * 
     * 从配置中获取
     */
    private int[] getGameIds() {
        var games = gateConfig.getGames();
        int[] ids = new int[games.size()];
        for (int i = 0; i < games.size(); i++) {
            ids[i] = games.get(i).getId();
        }
        return ids;
    }
}

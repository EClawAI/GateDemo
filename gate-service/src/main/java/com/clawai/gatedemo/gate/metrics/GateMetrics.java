package com.clawai.gatedemo.gate.metrics;

import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.service.PlayerService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * 网关自定义 Prometheus 指标：注册在线连接数、gRPC 池大小等 Gauge。
 */
@Component
public class GateMetrics {

    private final MeterRegistry meterRegistry;
    private final PlayerService playerService;
    private final GameGrpcClientPool gameGrpcClientPool;

    /**
     * @param meterRegistry       注册指标的目标注册表
     * @param playerService       提供在线人数
     * @param gameGrpcClientPool  提供池大小
     */
    public GateMetrics(MeterRegistry meterRegistry,
                       PlayerService playerService,
                       GameGrpcClientPool gameGrpcClientPool) {
        this.meterRegistry = meterRegistry;
        this.playerService = playerService;
        this.gameGrpcClientPool = gameGrpcClientPool;
    }

    /** 向 {@link MeterRegistry} 绑定 Gauge，重复调用会重复注册，故仅在启动时执行一次。 */
    @PostConstruct
    public void register() {
        Gauge.builder("active_connections", playerService, PlayerService::getOnlineCount)
                .description("Number of active WebSocket connections (players online)")
                .register(meterRegistry);

        Gauge.builder("grpc_pool_size", gameGrpcClientPool, GameGrpcClientPool::getPoolSize)
                .description("Number of gRPC connections in the game client pool")
                .register(meterRegistry);
    }
}

package com.clawai.gatedemo.gate.metrics;

import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.service.PlayerService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Gate service custom metrics for Prometheus.
 * Registers gauges for active_connections and grpc_pool_size.
 */
@Component
public class GateMetrics {

    private final MeterRegistry meterRegistry;
    private final PlayerService playerService;
    private final GameGrpcClientPool gameGrpcClientPool;

    public GateMetrics(MeterRegistry meterRegistry,
                       PlayerService playerService,
                       GameGrpcClientPool gameGrpcClientPool) {
        this.meterRegistry = meterRegistry;
        this.playerService = playerService;
        this.gameGrpcClientPool = gameGrpcClientPool;
    }

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

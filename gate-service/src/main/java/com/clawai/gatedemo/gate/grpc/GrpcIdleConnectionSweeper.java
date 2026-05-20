package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 对齐 lzwSLG/bsserver/icefire-gate {@code IdleConnectionSweeper}：周期调用 {@link GameGrpcClientPool#sweepIdleUpstreamConnections()}。
 */
@Component
public class GrpcIdleConnectionSweeper {

    private static final Logger log = LoggerFactory.getLogger(GrpcIdleConnectionSweeper.class);

    private final GateConfig gateConfig;
    private final GameGrpcClientPool pool;

    private ScheduledExecutorService scheduler;

    public GrpcIdleConnectionSweeper(GateConfig gateConfig, GameGrpcClientPool pool) {
        this.gateConfig = gateConfig;
        this.pool = pool;
    }

    @PostConstruct
    public void start() {
        long interval = Math.max(1, gateConfig.getGrpcPool().getIdleSweepIntervalSeconds());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gate-grpc-idle-sweeper");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                pool.sweepIdleUpstreamConnections();
            } catch (Exception e) {
                log.warn("grpc idle sweep failed", e);
            }
        }, interval, interval, TimeUnit.SECONDS);
        log.info("GrpcIdleConnectionSweeper started interval={}s idleClose={}s",
                interval, gateConfig.getGrpcPool().getIdleCloseSeconds());
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }
}

package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;

/**
 * 包内 helper：把 {@link FlowSessionManager} 的 package-private init/shutdown 与
 * {@link FlowSessionManagerTest.InMemoryRedisFlowStoreStub} 暴露给跨包测试（如
 * {@code com.clawai.gatedemo.gate.service.PlayerServiceTest}）。
 *
 * <p>仅测试用，不在 main 代码中调用。
 */
public final class FlowSessionManagerTestSupport {
    private FlowSessionManagerTestSupport() {}

    public static RedisFlowStore inMemoryStore(GateConfig.FlowConfig cfg) {
        return new FlowSessionManagerTest.InMemoryRedisFlowStoreStub(cfg);
    }

    public static void init(FlowSessionManager manager) {
        manager.init();
    }

    public static void shutdown(FlowSessionManager manager) {
        manager.shutdown();
    }
}

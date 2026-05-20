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

    /**
     * 强制设置下一次 {@link RedisFlowStore#crossTakeover} 的返回值。
     * 仅当传入的 store 是 {@link FlowSessionManagerTest.InMemoryRedisFlowStoreStub} 时生效，
     * 其它实现忽略调用。便于 JMH / 单测覆盖 {@code RESUMED_OWNER_CHANGED} 等分支。
     *
     * @param store         必须是 {@link #inMemoryStore} 返回的 stub
     * @param state         强制 cross outcome
     * @param previousOwner 上一持有者 gateId，便于 metrics 标签校验；null 时按 cur owner
     */
    public static void setCrossOutcome(RedisFlowStore store,
                                       RedisFlowStore.CrossState state,
                                       String previousOwner) {
        if (store instanceof FlowSessionManagerTest.InMemoryRedisFlowStoreStub stub) {
            stub.setCrossOutcome(state, previousOwner);
        }
    }

    public static void init(FlowSessionManager manager) {
        manager.init();
    }

    public static void shutdown(FlowSessionManager manager) {
        manager.shutdown();
    }
}

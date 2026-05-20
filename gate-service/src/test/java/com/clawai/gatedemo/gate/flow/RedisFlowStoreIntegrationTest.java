package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.config.GateConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通过真实 Redis（Testcontainers）验证 {@link RedisFlowStore} 的 Lua takeover 原子性、
 * 索引一致性与销毁路径。Docker 不可用时自动 Skip。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("isDockerAvailable")
class RedisFlowStoreIntegrationTest {

    static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    private static final String REDIS_IMAGE = "redis:7-alpine";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "redistest");

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("gate.redis.host", redis::getHost);
        registry.add("gate.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("gate.redis.password", () -> "redistest");
    }

    @Autowired
    private RedisFlowStore store;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private GateConfig gateConfig;

    @Test
    void takeover_createsFlowAndByPlayerIndex_andCanReadBack() {
        long playerId = 1001L;
        long now = System.currentTimeMillis();
        RedisFlowRecord record = new RedisFlowRecord(
                "flow-int-001", playerId, 7, gateConfig.getId(),
                now, now + 30_000, 0L, 0L);

        RedisFlowStore.TakeoverResult result = store.takeover(record);
        assertTrue(result.ok());
        assertNull(result.evictedFlowId(), "first takeover should not evict anything");

        RedisFlowRecord readBack = store.loadByFlowId("flow-int-001");
        assertNotNull(readBack);
        assertEquals(playerId, readBack.playerId());
        assertEquals(7, readBack.gameId());
        assertEquals(gateConfig.getId(), readBack.ownerGateId());

        String flowIdFromIndex = store.loadFlowIdByPlayer(playerId);
        assertEquals("flow-int-001", flowIdFromIndex);
    }

    @Test
    void takeover_secondCallReplacesPreviousFlow_atomically() {
        long playerId = 1002L;
        long now = System.currentTimeMillis();

        store.takeover(new RedisFlowRecord(
                "flow-A", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));
        RedisFlowStore.TakeoverResult second = store.takeover(new RedisFlowRecord(
                "flow-B", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));

        assertTrue(second.ok());
        assertEquals("flow-A", second.evictedFlowId());
        assertNull(store.loadByFlowId("flow-A"), "old flow hash must be DEL'd");
        assertNotNull(store.loadByFlowId("flow-B"));
        assertEquals("flow-B", store.loadFlowIdByPlayer(playerId));
    }

    @Test
    void markDetached_setsDetachedAtField() {
        long playerId = 1003L;
        long now = System.currentTimeMillis();
        store.takeover(new RedisFlowRecord(
                "flow-D", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));

        long detachedAt = System.currentTimeMillis();
        assertTrue(store.markDetached("flow-D", detachedAt));

        RedisFlowRecord readBack = store.loadByFlowId("flow-D");
        assertNotNull(readBack);
        assertEquals(detachedAt, readBack.detachedAt());
    }

    @Test
    void destroy_removesFlowHashAndByPlayerIndex() {
        long playerId = 1004L;
        long now = System.currentTimeMillis();
        store.takeover(new RedisFlowRecord(
                "flow-E", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));

        store.destroy("flow-E", playerId);

        assertNull(store.loadByFlowId("flow-E"));
        assertNull(store.loadFlowIdByPlayer(playerId));
    }

    @Test
    void destroy_doesNotRemoveByPlayerIndexWhenAlreadyReassigned() {
        long playerId = 1005L;
        long now = System.currentTimeMillis();
        store.takeover(new RedisFlowRecord(
                "flow-F1", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));
        // 模拟顶号：byplayer 已经指向 F2，但旧调用方仍试图按 F1 destroy
        store.takeover(new RedisFlowRecord(
                "flow-F2", playerId, 1, gateConfig.getId(), now, now + 30_000, 0L, 0L));

        store.destroy("flow-F1", playerId);

        assertEquals("flow-F2", store.loadFlowIdByPlayer(playerId),
                "byplayer index pointing to F2 must NOT be wiped by stale F1 destroy");
        assertNotNull(store.loadByFlowId("flow-F2"));
    }
}

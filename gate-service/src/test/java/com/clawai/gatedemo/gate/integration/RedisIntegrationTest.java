package com.clawai.gatedemo.gate.integration;

import com.clawai.gatedemo.gate.auth.TokenBlacklistService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test for TokenBlacklistService with real Redis via Testcontainers.
 * Skipped when Docker is not available (e.g. CI without Docker).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnabledIf("isDockerAvailable")
class RedisIntegrationTest {

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
    private TokenBlacklistService tokenBlacklistService;

    @Test
    void tokenBlacklistService_writeAndRead_fromRealRedis() {
        String jti = "jti-integration-test-001";
        long ttlSeconds = 60;

        // Initially not blacklisted
        assertFalse(tokenBlacklistService.isBlacklisted(jti));

        // Add to blacklist
        tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

        // Now should be blacklisted
        assertTrue(tokenBlacklistService.isBlacklisted(jti));
    }

    @Test
    void tokenBlacklistService_differentJti_notBlacklisted() {
        String jti1 = "jti-unique-1";
        String jti2 = "jti-unique-2";

        tokenBlacklistService.addToBlacklist(jti1, 60);

        assertTrue(tokenBlacklistService.isBlacklisted(jti1));
        assertFalse(tokenBlacklistService.isBlacklisted(jti2));
    }
}

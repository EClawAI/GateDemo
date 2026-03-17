package com.clawai.gatedemo.gate.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenBlacklistServiceTest {

    private RedisOperations<String, String> redisOperations;
    private ValueOperations<String, String> valueOps;
    private TokenBlacklistService tokenBlacklistService;

    @BeforeEach
    void setUp() {
        redisOperations = mock(RedisOperations.class);
        valueOps = mock(ValueOperations.class);
        when(redisOperations.opsForValue()).thenReturn(valueOps);
        tokenBlacklistService = new TokenBlacklistService(redisOperations);
    }

    @Test
    void addToBlacklist_callsRedisSetWithCorrectKey() {
        String jti = "jti-abc123";
        long ttlSeconds = 3600;

        tokenBlacklistService.addToBlacklist(jti, ttlSeconds);

        verify(valueOps).set(eq("token:blacklist:" + jti), eq("1"), eq(ttlSeconds), eq(TimeUnit.SECONDS));
    }

    @Test
    void addToBlacklist_ignoresNullJti() {
        tokenBlacklistService.addToBlacklist(null, 3600);

        verify(valueOps, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void isBlacklisted_whenKeyExists_returnsTrue() {
        String jti = "jti-existent";
        when(redisOperations.hasKey("token:blacklist:" + jti)).thenReturn(true);

        boolean result = tokenBlacklistService.isBlacklisted(jti);

        assertTrue(result);
        verify(redisOperations).hasKey("token:blacklist:" + jti);
    }

    @Test
    void isBlacklisted_whenKeyNotExists_returnsFalse() {
        String jti = "jti-non-existent";
        when(redisOperations.hasKey("token:blacklist:" + jti)).thenReturn(false);

        boolean result = tokenBlacklistService.isBlacklisted(jti);

        assertFalse(result);
        verify(redisOperations).hasKey("token:blacklist:" + jti);
    }

    @Test
    void isBlacklisted_withNullJti_returnsFalse() {
        boolean result = tokenBlacklistService.isBlacklisted(null);

        assertFalse(result);
        verify(redisOperations, never()).hasKey(anyString());
    }
}

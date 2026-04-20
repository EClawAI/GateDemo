package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GameDiscoveryServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedisMessageListenerContainer listenerContainer;

    @Mock
    private GameGrpcClientPool gameGrpcClientPool;

    private GateConfig gateConfig;
    private GameDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        gateConfig.getDiscovery().setEnabled(false);
        discoveryService = new GameDiscoveryService(
            stringRedisTemplate, listenerContainer, gateConfig, gameGrpcClientPool);
    }

    @Test
    void discoveryDisabledDoesNotLoadInstances() {
        discoveryService.init();
        verifyNoInteractions(gameGrpcClientPool);
    }

    @Test
    void getAvailableGamesReturnsEmpty() {
        assertTrue(discoveryService.getAvailableGames().isEmpty());
    }

    @Test
    void getGameMapReturnsEmpty() {
        assertTrue(discoveryService.getGameMap().isEmpty());
    }
}

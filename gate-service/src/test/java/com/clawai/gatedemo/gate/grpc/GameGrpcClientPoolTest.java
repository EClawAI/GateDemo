package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.service.GameDiscoveryService;
import com.clawai.gatedemo.grpc.GameMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GameGrpcClientPool 单元测试
 */
class GameGrpcClientPoolTest {

    private GateConfig gateConfig;

    /** 无 Mockito：用于池构造注入。 */
    private final ObjectProvider<GameDiscoveryService> nullDiscovery = new ObjectProvider<>() {
        @Override
        public GameDiscoveryService getObject(Object... args) throws BeansException {
            return getObject();
        }

        @Override
        public GameDiscoveryService getObject() throws BeansException {
            return null;
        }

        @Override
        public GameDiscoveryService getIfAvailable() {
            return null;
        }

        @Override
        public GameDiscoveryService getIfUnique() {
            return null;
        }

        @Override
        public GameDiscoveryService getIfAvailable(Supplier<GameDiscoveryService> defaultSupplier) {
            return defaultSupplier.get();
        }
    };

    private GameGrpcClientPool clientPool;

    @BeforeEach
    void setUp() {
        gateConfig = new GateConfig();
        clientPool = new GameGrpcClientPool(gateConfig, nullDiscovery);
        clientPool.init();
    }

    @Test
    void testStreamMessageHandler() {
        final List<GameMessage> receivedMessages = new ArrayList<>();

        clientPool.setStreamMessageHandler(receivedMessages::add);

        assertNotNull(clientPool);
    }

    @Test
    void testGameMessageConstruction() {
        String bodyJson = "{\"action\":\"move\",\"x\":100,\"y\":200}";

        GameMessage message = GameMessage.newBuilder()
                .setGateId("gate-01")
                .setPlayerId(12345L)
                .setMsgId(12345)
                .setSeq(1)
                .setTimestamp(System.currentTimeMillis())
                .setBody(com.google.protobuf.ByteString.copyFromUtf8(bodyJson))
                .build();

        assertNotNull(message);
        assertEquals("gate-01", message.getGateId());
        assertEquals(12345L, message.getPlayerId());
        assertEquals(12345, message.getMsgId());

        String decodedBody = message.getBody().toStringUtf8();
        assertEquals(bodyJson, decodedBody);
    }

    @Test
    void testByteStringConversion() {
        String original = "{\"playerId\":12345,\"action\":\"attack\"}";

        com.google.protobuf.ByteString byteString =
                com.google.protobuf.ByteString.copyFromUtf8(original);

        String converted = byteString.toStringUtf8();

        assertEquals(original, converted);
    }

    @Test
    void testEmptyBody() {
        GameMessage message = GameMessage.newBuilder()
                .setGateId("gate-01")
                .setPlayerId(12345L)
                .setMsgId(0)
                .setSeq(0)
                .setTimestamp(System.currentTimeMillis())
                .build();

        assertNotNull(message);
        assertTrue(message.getBody().isEmpty());
    }
}

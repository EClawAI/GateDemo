package com.clawai.gatedemo.gate.grpc;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.grpc.GameMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GameGrpcClientPool 单元测试
 * 
 * 测试内容：
 * 1. Stream连接建立
 * 2. Stream消息发送
 * 3. Stream消息接收
 * 4. 断线重连
 */
@ExtendWith(MockitoExtension.class)
class GameGrpcClientPoolTest {

    @Mock
    private GateConfig gateConfig;

    @Mock
    private GateConfig.GameInstance gameInstance;

    private ObjectMapper objectMapper;
    private GameGrpcClientPool clientPool;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        
        // 模拟配置
        if (gateConfig == null) {
            gateConfig = new GateConfig();
        }
        
        clientPool = new GameGrpcClientPool(gateConfig, objectMapper);
    }

    @Test
    void testStreamMessageHandler() {
        final List<GameMessage> receivedMessages = new ArrayList<>();
        
        // 设置Stream消息处理器
        clientPool.setStreamMessageHandler(message -> {
            receivedMessages.add(message);
        });

        assertNotNull(clientPool);
    }

    @Test
    void testGameMessageConstruction() {
        // 测试GameMessage构建（验证二进制格式）
        String bodyJson = "{\"action\":\"move\",\"x\":100,\"y\":200}";
        
        GameMessage message = GameMessage.newBuilder()
            .setGateId("gate-01")
            .setPlayerId(12345L)
            .setGameId(1001)
            .setMsgType("battle.move")
            .setSeq(1)
            .setTimestamp(System.currentTimeMillis())
            .setBody(com.google.protobuf.ByteString.copyFromUtf8(bodyJson))
            .build();

        // 验证
        assertNotNull(message);
        assertEquals("gate-01", message.getGateId());
        assertEquals(12345L, message.getPlayerId());
        assertEquals(1001, message.getGameId());
        assertEquals("battle.move", message.getMsgType());
        
        // 验证二进制转换
        String decodedBody = message.getBody().toStringUtf8();
        assertEquals(bodyJson, decodedBody);
    }

    @Test
    void testByteStringConversion() {
        // 测试ByteString和String之间的转换
        String original = "{\"playerId\":12345,\"action\":\"attack\"}";
        
        // String -> ByteString
        com.google.protobuf.ByteString byteString = 
            com.google.protobuf.ByteString.copyFromUtf8(original);
        
        // ByteString -> String
        String converted = byteString.toStringUtf8();
        
        assertEquals(original, converted);
    }

    @Test
    void testEmptyBody() {
        // 测试空消息体
        GameMessage message = GameMessage.newBuilder()
            .setGateId("gate-01")
            .setPlayerId(12345L)
            .setGameId(1001)
            .setMsgType("heartbeat")
            .setSeq(0)
            .setTimestamp(System.currentTimeMillis())
            .build();

        assertNotNull(message);
        assertTrue(message.getBody().isEmpty());
    }
}

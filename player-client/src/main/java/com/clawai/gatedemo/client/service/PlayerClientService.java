package com.clawai.gatedemo.client.service;

import com.clawai.gatedemo.client.config.PlayerConfig;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import com.clawai.gatedemo.proto.gate.HeartbeatAck;
import com.clawai.gatedemo.proto.game.CgBattleMove;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

/**
 * 演示用玩家客户端核心逻辑（二进制协议版）：基于 WebFlux ReactorNettyWebSocketClient
 * 发送 BinaryWebSocketFrame（14 字节头 + protobuf body）。
 */
@Service
public class PlayerClientService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientService.class);

    private static final short MSG_ID_AUTH = 0x1001;
    private static final short MSG_ID_HEARTBEAT = 0x2001;
    private static final short MSG_ID_HEARTBEAT_ACK = 0x2002;
    private static final short MSG_ID_BATTLE_MOVE = 0x3001;

    private static final int HEADER_SIZE = 14;

    private final PlayerConfig playerConfig;
    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
    private CountDownLatch latch;
    private WebSocketSession currentSession;

    public PlayerClientService(PlayerConfig playerConfig) {
        this.playerConfig = playerConfig;
    }

    public void connect() {
        String url = String.format("ws://%s:%d/ws", playerConfig.getHost(), playerConfig.getPort());
        logger.info("Connecting to Gate at {}", url);

        try {
            latch = new CountDownLatch(1);

            webSocketClient.execute(URI.create(url), session -> {
                currentSession = session;
                latch.countDown();

                authenticate(session);
                startHeartbeat(session);

                return session.receive()
                        .doOnNext(message -> handleMessage(message))
                        .then();
            }).subscribe();

            if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.info("Connected successfully!");
                startConsole();
            } else {
                logger.warn("Connection timeout - Gate service may not be running");
            }
        } catch (Exception e) {
            logger.error("Connection failed: {}", e.getMessage());
        }
    }

    private void authenticate(WebSocketSession session) {
        try {
            AuthRequest authReq = AuthRequest.newBuilder()
                    .setToken("demo-token")
                    .setGameId(1001)
                    .build();

            sendBinaryMessage(session, MSG_ID_AUTH, (short) 0x0000, authReq.toByteArray());
            logger.info("Auth message sent (binary)");
        } catch (Exception e) {
            logger.error("Failed to send auth: {}", e.getMessage());
        }
    }

    private void startHeartbeat(WebSocketSession session) {
        new Thread(() -> {
            while (currentSession != null && currentSession.isOpen()) {
                try {
                    Thread.sleep(playerConfig.getHeartbeatInterval() * 1000L);
                    if (currentSession != null && currentSession.isOpen()) {
                        ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                                .setTimestamp(System.currentTimeMillis()).build();
                        sendBinaryMessage(session, MSG_ID_HEARTBEAT, (short) 0, hb.toByteArray());
                        logger.debug("Heartbeat sent (binary)");
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Heartbeat error: {}", e.getMessage());
                }
            }
        }).start();
    }

    private void handleMessage(WebSocketMessage wsMessage) {
        try {
            DataBuffer payload = wsMessage.getPayload();
            ByteBuffer bb = payload.toByteBuffer();

            if (bb.remaining() < HEADER_SIZE) {
                logger.warn("帧过短: {} bytes", bb.remaining());
                return;
            }

            short flags = bb.getShort();
            short sequence = bb.getShort();
            short messageId = bb.getShort();
            int bodyLength = bb.getInt();
            int requestId = bb.getInt();

            byte[] bodyBytes = new byte[0];
            if (bodyLength > 0 && bb.remaining() >= bodyLength) {
                bodyBytes = new byte[bodyLength];
                bb.get(bodyBytes);
            }

            if (messageId == MSG_ID_AUTH) {
                AuthResponse resp = AuthResponse.parseFrom(bodyBytes);
                if (resp.getSuccess()) {
                    logger.info("Authentication successful! playerId={}", resp.getPlayerId());
                } else {
                    logger.warn("Authentication failed: {}", resp.getMessage());
                }
            } else if (messageId == MSG_ID_HEARTBEAT_ACK) {
                HeartbeatAck ack = HeartbeatAck.parseFrom(bodyBytes);
                logger.debug("Heartbeat acknowledged: serverTime={}", ack.getServerTime());
            } else {
                logger.info("Received message: msgId=0x{}, bodyLen={}",
                        Integer.toHexString(messageId & 0xFFFF), bodyLength);
            }
        } catch (Exception e) {
            logger.error("Failed to handle message: {}", e.getMessage());
        }
    }

    private void startConsole() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== Player Client Console (Binary Protocol) ===");
        System.out.println("Commands:");
        System.out.println("  move <x> <y> - Send battle.move");
        System.out.println("  heartbeat    - Send heartbeat");
        System.out.println("  quit         - Exit");
        System.out.println("=================================================\n");

        while (currentSession != null && currentSession.isOpen()) {
            System.out.print("> ");
            String line = scanner.nextLine();

            if (line.startsWith("move ")) {
                String[] parts = line.split(" ");
                if (parts.length >= 3) {
                    int x = Integer.parseInt(parts[1]);
                    int y = Integer.parseInt(parts[2]);
                    sendMoveMessage(x, y);
                }
            } else if ("heartbeat".equals(line)) {
                sendHeartbeatManual();
            } else if ("quit".equals(line)) {
                disconnect();
                break;
            }
        }
    }

    private void sendMoveMessage(int x, int y) {
        try {
            CgBattleMove move = CgBattleMove.newBuilder().setX(x).setY(y).build();
            sendBinaryMessage(currentSession, MSG_ID_BATTLE_MOVE, (short) 0, move.toByteArray());
            logger.info("Sent battle.move: x={}, y={}", x, y);
        } catch (Exception e) {
            logger.error("Failed to send game message: {}", e.getMessage());
        }
    }

    private void sendHeartbeatManual() {
        try {
            ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                    .setTimestamp(System.currentTimeMillis()).build();
            sendBinaryMessage(currentSession, MSG_ID_HEARTBEAT, (short) 0, hb.toByteArray());
            logger.info("Heartbeat sent");
        } catch (Exception e) {
            logger.error("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    /**
     * 构建 14 字节头 + protobuf body 的二进制帧并发送。
     */
    private void sendBinaryMessage(WebSocketSession session, short messageId, short flags, byte[] body) {
        if (session == null || !session.isOpen()) return;

        ByteBuf buf = Unpooled.buffer(HEADER_SIZE + body.length);
        buf.writeShort(flags);          // flags
        buf.writeShort(0);              // sequence
        buf.writeShort(messageId);      // messageId
        buf.writeInt(body.length);      // bodyLength
        buf.writeInt(0);                // requestId

        if (body.length > 0) {
            buf.writeBytes(body);
        }

        byte[] frameBytes = new byte[buf.readableBytes()];
        buf.readBytes(frameBytes);
        buf.release();

        DataBufferFactory factory = new DefaultDataBufferFactory();
        DataBuffer dataBuffer = factory.wrap(frameBytes);

        session.send(Mono.just(session.binaryMessage(db -> dataBuffer)))
                .subscribe(null, error -> logger.error("发送失败: {}", error.getMessage()));
    }

    private void disconnect() {
        if (currentSession != null) {
            currentSession.close();
        }
        logger.info("Disconnected");
    }
}

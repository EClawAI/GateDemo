package com.clawai.gatedemo.client.service;

import com.clawai.gatedemo.client.config.PlayerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.WebSocketSession;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

@Service
public class PlayerClientService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientService.class);

    private final PlayerConfig playerConfig;
    private final ObjectMapper objectMapper;
    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
    private CountDownLatch latch;
    private WebSocketSession currentSession;

    public PlayerClientService(PlayerConfig playerConfig, ObjectMapper objectMapper) {
        this.playerConfig = playerConfig;
        this.objectMapper = objectMapper;
    }

    public void connect() {
        latch = new CountDownLatch(1);
        
        String url = String.format("ws://%s:%d/ws", playerConfig.getHost(), playerConfig.getPort());
        logger.info("Connecting to Gate at {}", url);

        webSocketClient.execute(URI.create(url), session -> {
            currentSession = session;
            latch.countDown();
            
            // 发送认证消息
            authenticate(session);
            
            // 启动心跳
            startHeartbeat(session);
            
            // 接收消息
            return session.receive()
                .doOnNext(message -> {
                    String payload = message.getPayloadAsText();
                    logger.info("Received: {}", payload);
                    handleMessage(payload);
                })
                .then();
        }).block(Duration.ofSeconds(10));

        // 等待连接
        try {
            latch.await();
            logger.info("Connected successfully!");
            
            // 启动命令行交互
            startConsole();
        } catch (InterruptedException e) {
            logger.error("Connection failed: {}", e.getMessage());
        }
    }

    private void authenticate(WebSocketSession session) {
        try {
            Map<String, Object> authMessage = Map.of(
                "type", "auth",
                "player_id", playerConfig.getPlayerId()
            );
            session.send(Mono.just(session.textMessage(objectMapper.writeValueAsString(authMessage)))).block();
            logger.info("Auth message sent");
        } catch (Exception e) {
            logger.error("Failed to send auth: {}", e.getMessage());
        }
    }

    private void startHeartbeat(WebSocketSession session) {
        new Thread(() -> {
            while (currentSession != null && currentSession.isOpen()) {
                try {
                    Thread.sleep(playerConfig.getHeartbeatInterval() * 1000);
                    if (currentSession != null && currentSession.isOpen()) {
                        Map<String, Object> heartbeat = Map.of(
                            "type", "heartbeat",
                            "player_id", playerConfig.getPlayerId()
                        );
                        session.send(Mono.just(session.textMessage(objectMapper.writeValueAsString(heartbeat)))).block();
                        logger.debug("Heartbeat sent");
                    }
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    logger.error("Heartbeat error: {}", e.getMessage());
                }
            }
        }).start();
    }

    private void handleMessage(String payload) {
        try {
            Map<String, Object> message = objectMapper.readValue(payload, Map.class);
            String type = (String) message.get("type");
            
            if ("auth_ack".equals(type)) {
                logger.info("Authentication successful!");
            } else if ("heartbeat_ack".equals(type)) {
                logger.debug("Heartbeat acknowledged");
            } else if ("battle.update".equals(type) || "game_msg".equals(type)) {
                logger.info("Game message received: {}", message.get("body"));
            }
        } catch (Exception e) {
            logger.error("Failed to handle message: {}", e.getMessage());
        }
    }

    private void startConsole() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("\n=== Player Client Console ===");
        System.out.println("Commands:");
        System.out.println("  send <gameId> <message> - Send game message");
        System.out.println("  heartbeat - Send heartbeat");
        System.out.println("  quit - Exit");
        System.out.println("================================\n");

        while (currentSession != null && currentSession.isOpen()) {
            System.out.print("> ");
            String line = scanner.nextLine();
            
            if (line.startsWith("send ")) {
                String[] parts = line.split(" ", 3);
                if (parts.length >= 3) {
                    int gameId = Integer.parseInt(parts[1]);
                    sendGameMessage(gameId, parts[2]);
                }
            } else if ("heartbeat".equals(line)) {
                sendHeartbeat();
            } else if ("quit".equals(line)) {
                disconnect();
                break;
            }
        }
    }

    private void sendGameMessage(int gameId, String content) {
        try {
            Map<String, Object> message = Map.of(
                "type", "game_msg",
                "player_id", playerConfig.getPlayerId(),
                "game_id", gameId,
                "msg_type", "battle.move",
                "seq", System.currentTimeMillis(),
                "body", Map.of("action", content)
            );
            
            if (currentSession != null && currentSession.isOpen()) {
                currentSession.send(Mono.just(currentSession.textMessage(objectMapper.writeValueAsString(message)))).block();
                logger.info("Game message sent to game {}", gameId);
            }
        } catch (Exception e) {
            logger.error("Failed to send game message: {}", e.getMessage());
        }
    }

    private void sendHeartbeat() {
        try {
            Map<String, Object> message = Map.of(
                "type", "heartbeat",
                "player_id", playerConfig.getPlayerId()
            );
            
            if (currentSession != null && currentSession.isOpen()) {
                currentSession.send(Mono.just(currentSession.textMessage(objectMapper.writeValueAsString(message)))).block();
                logger.info("Heartbeat sent");
            }
        } catch (Exception e) {
            logger.error("Failed to send heartbeat: {}", e.getMessage());
        }
    }

    private void disconnect() {
        if (currentSession != null) {
            currentSession.close();
        }
        logger.info("Disconnected");
    }
}
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

/**
 * 演示用玩家客户端核心逻辑：基于 WebFlux {@link ReactorNettyWebSocketClient} 连接 gate WebSocket，
 * 完成鉴权、周期性心跳、下行解析与控制台交互发令，用于端到端验证文本协议路径。
 */
@Service
public class PlayerClientService {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientService.class);

    private final PlayerConfig playerConfig;
    private final ObjectMapper objectMapper;
    /** Spring WebFlux 自带的响应式 WebSocket 客户端，与 Netty 底层兼容 */
    private final ReactorNettyWebSocketClient webSocketClient = new ReactorNettyWebSocketClient();
    /** 阻塞主线程直至会话回调就绪，避免控制台在连接建立前启动 */
    private CountDownLatch latch;
    /** 当前 WebSocket 会话，心跳线程与控制台通过其发送；非 volatile，依赖同线程写入后可见的用法 */
    private WebSocketSession currentSession;

    /**
     * @param playerConfig  {@code player.*} 绑定配置
     * @param objectMapper  与网关 JSON 字段命名一致的序列化器
     */
    public PlayerClientService(PlayerConfig playerConfig, ObjectMapper objectMapper) {
        this.playerConfig = playerConfig;
        this.objectMapper = objectMapper;
    }

    /**
     * 非阻塞发起 WebSocket 执行流，等待短时闩锁后启动控制台循环。
     * 下行在 reactive 链中解析日志，心跳在独立线程中周期性发送。
     */
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
                        .doOnNext(message -> {
                            String payload = message.getPayloadAsText();
                            logger.info("Received: {}", payload);
                            handleMessage(payload);
                        })
                        .then();
            }).subscribe(); // 移除 block() 调用，使用非阻塞方式

            if (latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                logger.info("Connected successfully!");
                startConsole();
            } else {
                logger.warn("Connection timeout - Gate service may not be running");
            }
        } catch (Exception e) {
            logger.error("Connection failed: {}", e.getMessage());
            logger.info("Hint: Start Gate service first: cd gate-service && mvn spring-boot:run");
        }
    }

    private void authenticate(WebSocketSession session) {
        try {
            Map<String, Object> authMessage = Map.of(
                "type", "auth",
                "player_id", playerConfig.getPlayerId()
            );
            // 异步发送，不使用 block()
            session.send(Mono.just(session.textMessage(objectMapper.writeValueAsString(authMessage))))
                .subscribe(null, error -> logger.error("Failed to send auth: {}", error.getMessage()));
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
                        session.send(Mono.just(session.textMessage(objectMapper.writeValueAsString(heartbeat))))
                            .subscribe(null, error -> logger.debug("Heartbeat send error: {}", error.getMessage()));
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

    /**
     * 解析文本帧 JSON，按 {@code type} 打日志；鉴权确认与游戏下行在此分支处理。
     *
     * @param payload 原始文本消息体
     */
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

    /** 阻塞读取标准输入，解析 send/heartbeat/quit 指令并与当前会话交互 */
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

    /**
     * 组装 {@code game_msg} 并同步发送（演示用）；需会话仍打开。
     *
     * @param gameId  目标逻辑游戏 ID
     * @param content 写入 body.action 的文本
     */
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

    /** 手动触发一次心跳帧，供控制台命令调用 */
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

    /** 关闭 WebSocket 会话并结束控制台循环 */
    private void disconnect() {
        if (currentSession != null) {
            currentSession.close();
        }
        logger.info("Disconnected");
    }
}
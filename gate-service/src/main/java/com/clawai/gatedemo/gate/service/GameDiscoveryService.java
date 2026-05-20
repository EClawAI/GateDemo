package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 从 Redis 游戏注册表加载实例、订阅上下线与状态事件，并同步维护 {@link GameGrpcClientPool}，使网关 gRPC 目标与真实 game-service 拓扑一致。
 */
@Service
public class GameDiscoveryService {

    private static final Logger logger = LoggerFactory.getLogger(GameDiscoveryService.class);

    private static final String REGISTRY_KEY_PREFIX = "game:registry:";
    private static final String EVENT_CHANNEL = "game:events";

    private final StringRedisTemplate stringRedisTemplate;
    private final RedisMessageListenerContainer listenerContainer;
    private final GateConfig gateConfig;
    private final GameGrpcClientPool gameGrpcClientPool;

    /** 内存中的 gameId → 实例信息，与连接池保持同步 */
    private final Map<Integer, GameInstance> gameMap = new ConcurrentHashMap<>();

    /**
     * @param stringRedisTemplate 读 {@code game:registry:*} 纯字符串值（与 game-service 写入一致）
     * @param listenerContainer   订阅游戏事件频道
     * @param gateConfig          是否启用发现、过期阈值等
     * @param gameGrpcClientPool  增删 gRPC 连接
     */
    public GameDiscoveryService(StringRedisTemplate stringRedisTemplate,
                               RedisMessageListenerContainer listenerContainer,
                               GateConfig gateConfig,
                               GameGrpcClientPool gameGrpcClientPool) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.listenerContainer = listenerContainer;
        this.gateConfig = gateConfig;
        this.gameGrpcClientPool = gameGrpcClientPool;
    }

    /**
     * 若启用发现则全量加载注册表并订阅事件；关闭时不访问 Redis。
     */
    @PostConstruct
    public void init() {
        if (!gateConfig.getDiscovery().isEnabled()) {
            logger.info("Game discovery is disabled");
            return;
        }
        loadGameInstances();
        subscribeToEvents();
    }

    private void loadGameInstances() {
        try {
            Set<String> keys = stringRedisTemplate.keys(REGISTRY_KEY_PREFIX + "*");
            if (keys != null) {
                for (String key : keys) {
                    String value = stringRedisTemplate.opsForValue().get(key);
                    if (value != null) {
                        GameInstance instance = parseGameInstance(key, value);
                        if (instance != null && instance.isAvailable()) {
                            gameMap.put(instance.getId(), instance);
                            connectToGame(instance);
                        }
                    }
                }
            }
            logger.info("Loaded {} game instances", gameMap.size());
        } catch (Exception e) {
            logger.warn("Failed to load game instances: {}", e.getMessage());
        }
    }

    private void subscribeToEvents() {
        try {
            listenerContainer.addMessageListener(new MessageListener() {
                @Override
                public void onMessage(Message message, byte[] pattern) {
                    String event = new String(message.getBody());
                    handleEvent(event);
                }
            }, new ChannelTopic(EVENT_CHANNEL));
            logger.info("Subscribed to game events channel");
        } catch (Exception e) {
            logger.warn("Failed to subscribe to game events: {}", e.getMessage());
        }
    }

    private void handleEvent(String event) {
        try {
            String[] parts = event.split(":");
            if (parts.length < 2) {
                return;
            }

            String type = parts[0];
            int gameId = Integer.parseInt(parts[1]);

            switch (type) {
                case "REGISTER":
                    if (parts.length >= 3) {
                        String value = event.substring(event.indexOf(":") + 1);
                        value = value.substring(value.indexOf(":") + 1);
                        GameInstance instance = parseGameInstance(REGISTRY_KEY_PREFIX + gameId, value);
                        if (instance != null && instance.isAvailable()) {
                            gameMap.put(gameId, instance);
                            connectToGame(instance);
                            logger.info("Game registered: gameId={}", gameId);
                        }
                    }
                    break;

                case "UNREGISTER":
                    disconnectFromGame(gameId);
                    gameMap.remove(gameId);
                    logger.info("Game unregistered: gameId={}", gameId);
                    break;

                case "UPDATE":
                    if (parts.length >= 3) {
                        int status = Integer.parseInt(parts[2]);
                        GameInstance existing = gameMap.get(gameId);
                        if (existing != null) {
                            existing.setStatus(status);
                            if (status == 2 && !gameGrpcClientPool.hasConnection(gameId)) {
                                connectToGame(existing);
                            } else if (status != 2) {
                                disconnectFromGame(gameId);
                            }
                            logger.info("Game status updated: gameId={}, status={}", gameId, status);
                        }
                    }
                    break;
            }
        } catch (Exception e) {
            logger.warn("Failed to handle event: {}, error: {}", event, e.getMessage());
        }
    }

    /**
     * 周期性比对 Redis 注册表与本地 map：缺失或时间戳过旧则视为下线并断开 gRPC。
     */
    @Scheduled(fixedRate = 10000)
    public void checkStaleGames() {
        if (!gateConfig.getDiscovery().isEnabled()) {
            return;
        }

        try {
            long staleThreshold = gateConfig.getDiscovery().getStaleThreshold();
            Set<String> keys = stringRedisTemplate.keys(REGISTRY_KEY_PREFIX + "*");

            if (keys == null) {
                return;
            }

            for (String key : keys) {
                String value = stringRedisTemplate.opsForValue().get(key);
                if (value == null) {
                    int gameId = extractGameId(key);
                    handleGameOffline(gameId);
                    continue;
                }

                String[] parts = value.split(":");
                if (parts.length >= 4) {
                    long timestamp = Long.parseLong(parts[3]);
                    if (System.currentTimeMillis() - timestamp > staleThreshold) {
                        int gameId = extractGameId(key);
                        handleGameOffline(gameId);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Failed to check stale games: {}", e.getMessage());
        }
    }

    private void handleGameOffline(int gameId) {
        if (gameMap.containsKey(gameId)) {
            disconnectFromGame(gameId);
            gameMap.remove(gameId);
            logger.info("Game offline detected: gameId={}", gameId);
        }
    }

    private void connectToGame(GameInstance instance) {
        if (instance == null || !instance.isAvailable()) {
            return;
        }
        if (gateConfig.getGrpcPool().isLazyConnect()) {
            return;
        }
        gameGrpcClientPool.addConnection(instance.getId(), instance.getHost(), instance.getPort());
    }

    /**
     * 供 {@link com.clawai.gatedemo.gate.grpc.GameGrpcClientPool} 按需建连时查询 Redis 缓存的实例地址。
     */
    public GameInstance getGameInstance(int gameId) {
        return gameMap.get(gameId);
    }

    private void disconnectFromGame(int gameId) {
        gameGrpcClientPool.removeConnection(gameId);
    }

    /** @return 当前状态为可用的游戏实例列表（拷贝） */
    public List<GameInstance> getAvailableGames() {
        return gameMap.values().stream()
            .filter(GameInstance::isAvailable)
            .collect(Collectors.toList());
    }

    /** @return 本地缓存的游戏映射快照，修改不影响内部 map */
    public Map<Integer, GameInstance> getGameMap() {
        return new HashMap<>(gameMap);
    }

    private GameInstance parseGameInstance(String key, String value) {
        try {
            String[] parts = value.split(":");
            if (parts.length < 3) {
                return null;
            }

            int gameId = extractGameId(key);
            String host = parts[0];
            int port = Integer.parseInt(parts[1]);
            int status = Integer.parseInt(parts[2]);

            GameInstance instance = new GameInstance();
            instance.setId(gameId);
            instance.setHost(host);
            instance.setPort(port);
            instance.setStatus(status);
            return instance;
        } catch (Exception e) {
            logger.warn("Failed to parse game instance: {}", e.getMessage());
            return null;
        }
    }

    private int extractGameId(String key) {
        try {
            String id = key.replace(REGISTRY_KEY_PREFIX, "");
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static class GameInstance {
        private int id;
        private String host;
        private int port;
        private int status;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public int getStatus() { return status; }
        public void setStatus(int status) { this.status = status; }

        /** 与注册约定一致：status==2 表示可对外服务 */
        public boolean isAvailable() {
            return status == 2;
        }
    }
}

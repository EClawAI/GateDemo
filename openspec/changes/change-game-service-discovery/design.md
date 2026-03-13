## Context

当前Gate连接哪些Game是在gate的配置文件中手动指定的：
```yaml
gate:
  games:
    - id: 1001
      host: localhost
      port: 9090
```

这种方式存在以下问题：
- 新增Game时需要手动修改所有Gate的配置
- 无法动态感知Game的上下线状态
- 配置复杂，难以维护

## Architecture

```
┌─────────────┐         ┌─────────────┐
│  Game       │ ──────► │   Redis     │
│  (注册/注销) │         │ (服务注册)   │
└─────────────┘         └──────┬──────┘
                               │
                               │ 订阅/查询
                               ▼
                        ┌─────────────┐
                        │   Gate      │
                        │ (服务发现)   │
                        └─────────────┘
```

## Redis Key设计

### 服务注册

```
# Game服务注册信息
Key: game:registry:{gameId}
Value: {host}:{port}:{status}:{timestamp}
TTL: 60秒（心跳续期）

# 示例
game:registry:1001 = localhost:9090:2:1709900000000
```

### 服务状态

| 状态值 | 说明 |
|--------|------|
| 0 | 服务未启动 |
| 1 | 服务已启动但不可用 |
| 2 | 服务可用 |

### 事件通道

```
# 事件发布/订阅
Channel: game:events

# 事件格式
Event: {type}:{gameId}:{host}:{port}
Type: REGISTER | UNREGISTER | UPDATE
```

## Game端实现

### 服务注册

```java
@Service
public class GameRegistryService {

    private static final String REGISTRY_KEY = "game:registry:";
    private static final String EVENT_CHANNEL = "game:events";
    private static final long TTL_SECONDS = 60;

    @PostConstruct
    public void register() {
        // 注册服务
        String value = String.format("%s:%d:2:%d", 
            gameConfig.getHost(), 
            gameConfig.getPort(),
            System.currentTimeMillis());
        
        redisTemplate.opsForValue().set(
            REGISTRY_KEY + gameConfig.getId(),
            value,
            TTL_SECONDS,
            TimeUnit.SECONDS
        );
        
        // 发布注册事件
        redisTemplate.convertAndSend(EVENT_CHANNEL, 
            "REGISTER:" + gameConfig.getId() + ":" + value);
    }

    @PreDestroy
    public void unregister() {
        // 删除注册信息
        redisTemplate.delete(REGISTRY_KEY + gameConfig.getId());
        
        // 发布注销事件
        redisTemplate.convertAndSend(EVENT_CHANNEL, 
            "UNREGISTER:" + gameConfig.getId());
    }

    @Scheduled(fixedRate = 30000)
    public void heartbeat() {
        // 续期注册信息
        // ... 同register逻辑
    }
}
```

### 状态变更

```java
public void setStatus(GameStatus status) {
    // 更新注册信息中的状态
    // 发布状态变更事件
    redisTemplate.convertAndSend(EVENT_CHANNEL,
        "UPDATE:" + gameConfig.getId() + ":" + status.getValue());
}
```

## Gate端实现

### 服务发现

```java
@Service
public class GameDiscoveryService {

    private static final String REGISTRY_KEY = "game:registry:";
    private static final String EVENT_CHANNEL = "game:events";

    private final Map<Integer, GameInstance> gameMap = new ConcurrentHashMap<>();
    private final RedisMessageListenerContainer listenerContainer;

    @PostConstruct
    public void init() {
        // 1. 全量加载当前注册的Game
        Set<String> keys = redisTemplate.keys(REGISTRY_KEY + "*");
        for (String key : keys) {
            GameInstance instance = parseRegistry(key);
            if (instance != null && instance.isAvailable()) {
                gameMap.put(instance.getId(), instance);
            }
        }

        // 2. 订阅Game变更事件
        listenerContainer.addMessageListener(
            (message, pattern) -> handleEvent(message.toString()),
            new PatternTopic("game:events")
        );
    }

    private void handleEvent(String event) {
        String[] parts = event.split(":");
        String type = parts[0];
        int gameId = Integer.parseInt(parts[1]);

        switch (type) {
            case "REGISTER":
                // 新增Game
                gameMap.put(gameId, parseGameInfo(parts));
                connectToGame(gameId);
                break;
            case "UNREGISTER":
                // 移除Game
                disconnectFromGame(gameId);
                gameMap.remove(gameId);
                break;
            case "UPDATE":
                // 更新Game状态
                GameInstance instance = gameMap.get(gameId);
                if (instance != null) {
                    instance.setStatus(parseStatus(parts[2]));
                    if (!instance.isAvailable()) {
                        disconnectFromGame(gameId);
                    }
                }
                break;
        }
    }

    public List<GameInstance> getAvailableGames() {
        return gameMap.values().stream()
            .filter(GameInstance::isAvailable)
            .collect(Collectors.toList());
    }
}
```

### 连接管理

```java
private void connectToGame(int gameId) {
    GameInstance instance = gameMap.get(gameId);
    if (instance != null) {
        // 建立gRPC连接
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(instance.getHost(), instance.getPort())
            .usePlaintext()
            .build();
        
        gameChannels.put(gameId, channel);
    }
}

private void disconnectFromGame(int gameId) {
    ManagedChannel channel = gameChannels.remove(gameId);
    if (channel != null) {
        channel.shutdown();
    }
}
```

## 配置示例

### Game服务配置

```yaml
game:
  id: 1001
  host: ${HOST:localhost}
  port: ${GRPC_PORT:9090}
  registry:
    heartbeat-interval: 30000
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:redistest}
```

### Gate服务配置

```yaml
gate:
  id: gate-01
  discovery:
    enabled: true
    initial-load-timeout: 5000
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:redistest}
```

## 错误处理

1. **Redis连接失败**：使用本地缓存的Game列表，定期重试连接
2. **Game连接失败**：标记Game为不可用，等待下次事件或定期重试
3. **事件丢失**：定期全量同步Game列表作为兜底

## 安全性

1. **服务认证**：可以通过Redis密码进行认证
2. **服务隔离**：不同环境的Game使用不同的Redis数据库

## TTL保活机制

### 工作原理

Game服务通过定时心跳来续期Redis中的注册信息，实现保活：

```
TTL = 60秒
心跳间隔 = 30秒

Game启动 → 注册(TTL=60s)
    ↓
每30秒心跳 → 续期TTL=60s
    ↓
正常运行时一直续期
    ↓
Game关闭 → 手动删除注册信息
    ↓
异常退出 → 无心跳 → TTL过期(60s) → 自动失效
```

### 实现细节

```java
@Service
public class GameRegistryService {

    private static final long TTL_SECONDS = 60;
    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL)
    public void heartbeat() {
        // 使用setNX + expire 或者 直接set + expire
        // 每次设置都刷新TTL，实现续期
        String value = buildRegistryValue();
        
        redisTemplate.opsForValue().set(
            REGISTRY_KEY + gameConfig.getId(),
            value,
            TTL_SECONDS,
            TimeUnit.SECONDS
        );
        
        // 不需要每次都发布事件，减少Redis压力
        // 事件只在状态变更时发布
    }
}
```

### TTL过期场景

1. **正常关闭**：Game调用`@PreDestroy`主动删除注册信息
2. **异常退出**：进程崩溃/被杀，无心跳 → TTL过期 → Gate检测到不可用
3. **网络断开**：网络异常导致心跳失败 → TTL过期

## Gate异常处理

### Game异常下线检测

Gate通过以下方式检测Game异常下线：

```java
@Service
public class GameDiscoveryService {

    private static final long STALE_THRESHOLD = 120000; // 2分钟

    @Scheduled(fixedRate = 10000)
    public void checkStaleGames() {
        Set<String> keys = redisTemplate.keys(REGISTRY_KEY + "*");
        
        for (String key : keys) {
            Object value = redisTemplate.opsForValue().get(key);
            if (value == null) {
                // Key已过期被删除
                int gameId = extractGameId(key);
                handleGameOffline(gameId);
                continue;
            }
            
            // 检查时间戳是否过期
            long timestamp = extractTimestamp(value.toString());
            if (System.currentTimeMillis() - timestamp > STALE_THRESHOLD) {
                // 超过2分钟没有更新，视为下线
                int gameId = extractGameId(key);
                handleGameOffline(gameId);
            }
        }
    }

    private void handleGameOffline(int gameId) {
        // 1. 断开连接
        disconnectFromGame(gameId);
        
        // 2. 从可用列表移除
        gameMap.remove(gameId);
        
        // 3. 通知相关组件
        eventBus.publish(new GameOfflineEvent(gameId));
    }
}
```

### Gate收到异常下线事件

```java
private void handleEvent(String event) {
    String[] parts = event.split(":");
    String type = parts[0];
    int gameId = Integer.parseInt(parts[1]);

    switch (type) {
        case "REGISTER":
            // 新增Game
            gameMap.put(gameId, parseGameInfo(parts));
            connectToGame(gameId);
            break;
            
        case "UNREGISTER":
            // 正常下线：主动注销
            disconnectFromGame(gameId);
            gameMap.remove(gameId);
            break;
            
        case "UPDATE":
            // 状态变更
            handleGameUpdate(parts);
            break;
    }
}

private void handleGameUpdate(String[] parts) {
    int gameId = Integer.parseInt(parts[1]);
    int status = Integer.parseInt(parts[2]);
    
    GameInstance instance = gameMap.get(gameId);
    if (instance != null) {
        instance.setStatus(status);
        
        if (status == 2) {
            // 变为可用，尝试连接
            if (!gameChannels.containsKey(gameId)) {
                connectToGame(gameId);
            }
        } else {
            // 变为不可用，断开连接
            disconnectFromGame(gameId);
        }
    }
}
```

### 连接失败处理

```java
private void connectToGame(int gameId) {
    GameInstance instance = gameMap.get(gameId);
    if (instance == null || !instance.isAvailable()) {
        return;
    }
    
    try {
        ManagedChannel channel = ManagedChannelBuilder
            .forAddress(instance.getHost(), instance.getPort())
            .usePlaintext()
            .keepAliveTime(30, TimeUnit.SECONDS)
            .keepAliveTimeout(10, TimeUnit.SECONDS)
            .build();
        
        // 尝试ping确认连接可用
        GameServiceGrpc.GameServiceBlockingStub stub = 
            GameServiceGrpc.newBlockingStub(channel);
        stub.ping(PingRequest.getDefaultInstance());
        
        gameChannels.put(gameId, channel);
        
    } catch (Exception e) {
        // 连接失败，标记为暂时不可用
        logger.warn("Failed to connect to game {}: {}", gameId, e.getMessage());
        // 不要立即重试，等待下次事件触发
    }
}
```

### 故障恢复流程

```
Game异常下线
    ↓
Gate检测到下线(通过TTL过期或事件)
    ↓
断开gRPC连接
    ↓
从gameMap中移除
    ↓
    ↓
Game恢复后重新注册
    ↓
Gate收到REGISTER事件
    ↓
尝试建立新连接
    ↓
恢复服务
```

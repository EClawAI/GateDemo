# gRPC 连接池架构说明

## 架构概述

Gate 服务维护一个 **gRPC 连接池**，根据 `gameId` 路由消息到对应的 Game 服务实例。

### 架构图

```
┌─────────────────────────────────────────┐
│           Gate Service                  │
│                                         │
│  ┌───────────────────────────────────┐  │
│  │     GameGrpcClientPool            │  │
│  │                                   │  │
│  │  Map<gameId, GrpcConnection>      │  │
│  │    ├─ 1001 → Channel → Game-1     │  │
│  │    ├─ 1002 → Channel → Game-2     │  │
│  │    └─ 1003 → Channel → Game-3     │  │
│  └───────────────────────────────────┘  │
└─────────────────────────────────────────┘
           │         │         │
           ↓         ↓         ↓
     ┌─────────┐ ┌─────────┐ ┌─────────┐
     │ Game-1  │ │ Game-2  │ │ Game-3  │
     │ :9091   │ │ :9092   │ │ :9093   │
     └─────────┘ └─────────┘ └─────────┘
```

## 配置说明

### Gate 服务配置（application.yml）

```yaml
gate:
  id: gate-01
  port: 8888
  # Game 服务列表（支持多实例）
  games:
    - id: 1001
      host: localhost
      port: 9091
    - id: 1002
      host: localhost
      port: 9092
    - id: 1003
      host: localhost
      port: 9093
```

**环境变量覆盖：**
```bash
# Gate 服务
export GATE_ID=gate-01
export GATE_PORT=8888

# Game-1 配置
export GAME_1001_HOST=192.168.1.101
export GAME_1001_PORT=9091

# Game-2 配置
export GAME_1002_HOST=192.168.1.102
export GAME_1002_PORT=9092
```

### Game 服务配置（application.yml）

```yaml
game:
  id: ${GAME_ID:1001}  # Game ID（数字）

grpc:
  port: ${GRPC_PORT:9090}  # gRPC 监听端口
```

**启动参数：**
```bash
# 启动 Game-1
java -jar game-service.jar --game.id=1001 --grpc.port=9091

# 启动 Game-2
java -jar game-service.jar --game.id=1002 --grpc.port=9092

# 或使用环境变量
GAME_ID=1001 GRPC_PORT=9091 java -jar game-service.jar
```

## 路由逻辑

### 消息路由流程

1. 玩家发送消息到 Gate（WebSocket）
2. Gate 解析消息，提取 `gameId`
3. 根据 `gameId` 从连接池获取对应 Channel
4. 通过 gRPC 发送到对应的 Game 服务

### 代码示例

```java
// PlayerService.java
public void forwardToGame(Long playerId, Integer gameId, PlayerMessage message) {
    // 根据 gameId 路由到对应的 Game 服务
    boolean success = gameGrpcClientPool.sendGameMessage(
        gameId,      // ← 路由键
        playerId,
        message.getMsgType(),
        message.getSeq(),
        message.getBody()
    );
}
```

## 连接管理

### 统一连接池

`GameGrpcClientPool` 是 Gate 与所有 Game 实例通信的**唯一连接组件**。所有 gRPC 连接的创建、复用、销毁均由该池管理。

连接来源有两种，可并存：

| 来源 | 何时生效 | 行为 |
|------|----------|------|
| **静态配置**（`gate.games`）| Gate 启动时 | `@PostConstruct` 遍历 `gate.games` 列表调用 `addConnection` |
| **服务发现**（`gate.discovery.enabled=true`）| 运行时 Redis 事件/轮询 | `GameDiscoveryService` 监听注册/注销事件后调用 `addConnection`/`removeConnection` |

**重叠策略**：当同一 `gameId` 同时在 `gate.games` 和 Discovery 中出现时，以**先到先得**为准 —— `addConnection` 对已有 gameId 直接跳过并打印 WARN 日志，不会创建重复连接也不会覆盖。如需以 Discovery 为唯一来源，可在 `gate.games` 中不配置该 gameId。

### 自动初始化

Gate 服务启动时，自动从配置读取所有 Game 服务并建立连接：

```java
@PostConstruct
public void init() {
    for (GateConfig.GameInstance game : gateConfig.getGames()) {
        addConnection(game.getId(), game.getHost(), game.getPort());
    }
}
```

### 动态添加/移除

```java
// 添加新的 Game 服务（由 Discovery 或手动调用）
gameGrpcClientPool.addConnection(1004, "localhost", 9094);

// 移除 Game 服务
gameGrpcClientPool.removeConnection(1003);
```

### 心跳与健康检查

- `GrpcHeartbeatManager` 遍历池中所有连接（含静态和 Discovery 来源），按配置周期发送心跳。
- Stream `onError` 时触发 `scheduleReconnect`（可配置延迟），自动重新建立 Stream 连接。
- 连接被 `removeConnection` 后不再重连；Discovery 再次注册时会重新 `addConnection`。

## 部署示例

### 单机多实例（开发环境）

```bash
# 启动 Game-1
GAME_ID=1001 GRPC_PORT=9091 java -jar game-service.jar &

# 启动 Game-2
GAME_ID=1002 GRPC_PORT=9092 java -jar game-service.jar &

# 启动 Gate
GATE_ID=gate-01 java -jar gate-service.jar
```

### 多机部署（生产环境）

```yaml
# Gate 服务配置
gate:
  games:
    - id: 1001
      host: 192.168.1.101  # Game-1 服务器
      port: 9090
    - id: 1002
      host: 192.168.1.102  # Game-2 服务器
      port: 9090
    - id: 1003
      host: 192.168.1.103  # Game-3 服务器
      port: 9090
```

## 扩展性

### 负载均衡（未来）

当前实现是 **gameId 哈希路由**，未来可以扩展：

1. **轮询负载均衡**：同一 gameId 多个实例
2. **一致性哈希**：动态扩缩容
3. **服务发现**：集成 Nacos/Consul

### 连接池配置

```yaml
gate:
  grpc-pool:
    keep-alive-time: 30          # keepAlive 间隔（秒）
    keep-alive-timeout: 10       # keepAlive 超时（秒）
    keep-alive-without-calls: true
    reconnect-delay: 5000        # Stream 断开后重连延迟（毫秒）
    heartbeat-interval: 30000    # 心跳发送周期（毫秒）
```

## 监控指标

### 连接池状态

```java
// 连接数
int poolSize = gameGrpcClientPool.getPoolSize();

// 各连接状态（待实现）
Map<Integer, ConnectionStatus> status = gameGrpcClientPool.getConnectionStatus();
```

### 日志示例

```
=== 初始化 gRPC 连接池 ===
🔗 创建 Game 1001 连接：localhost:9091
✅ Game 1001 连接已建立
🔗 创建 Game 1002 连接：localhost:9092
✅ Game 1002 连接已建立
✅ gRPC 连接池初始化完成，连接数：2
```

## 故障处理

### Game 服务宕机

- Stream `onError` 触发后，`scheduleReconnect` 自动在配置延迟后重连。
- 若 Discovery 检测到实例下线，`removeConnection` 会释放连接；实例恢复后 Discovery 重新注册触发 `addConnection`。

### Gate 服务重启

- 连接池自动重建
- 玩家需重连 WebSocket

## 最佳实践

1. **Game ID 规划**：按游戏类型或区域分配
2. **端口规划**：统一使用 9090+ 端口段
3. **监控告警**：监控连接池状态和发送成功率
4. **优雅关闭**：先停 Gate，再停 Game

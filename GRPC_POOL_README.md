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
// 添加新的 Game 服务
gameGrpcClientPool.addConnection(1004, "localhost", 9094);

// 移除 Game 服务
gameGrpcClientPool.removeConnection(1003);
```

### 心跳保持

每个连接独立维护双向流心跳：
- 默认 30 秒 keepalive
- 自动重连（待实现）

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

### 连接池配置（未来）

```yaml
gate:
  grpc:
    pool:
      max-connections: 100
      connect-timeout: 5000
      idle-timeout: 300000
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

- ❌ 当前：连接断开，发送失败
- ✅ 计划：自动重连 + 告警

### Gate 服务重启

- 连接池自动重建
- 玩家需重连 WebSocket

## 最佳实践

1. **Game ID 规划**：按游戏类型或区域分配
2. **端口规划**：统一使用 9090+ 端口段
3. **监控告警**：监控连接池状态和发送成功率
4. **优雅关闭**：先停 Gate，再停 Game

# gRPC 集成说明

## 架构概述

Gate 服务和 Game 服务之间使用 **gRPC 长连接**进行通信，替代原有的 HTTP 短连接。

### 优势

| 特性 | HTTP | gRPC |
|------|------|------|
| 连接方式 | 短连接（每次请求新建） | 长连接（复用） |
| 序列化 | JSON | Protobuf（更小更快） |
| 并发 | 低 | HTTP/2 多路复用 |
| 心跳 | 无 | 双向流支持 |
| 性能 | 基准 | 3-5x 提升 |

### 架构图

```
┌─────────────┐      gRPC 长连接      ┌─────────────┐
│  Gate       │ ←───────────────────→ │  Game       │
│  Service    │    Port: 9090         │  Service    │
│             │                       │             │
│  WebSocket  │                       │  gRPC       │
│  Port: 8888 │                       │  Server     │
└─────────────┘                       └─────────────┘
      ↑                                      ↑
      │                                      │
  玩家连接                               游戏逻辑
```

## Proto 定义

位置：`proto/game_service.proto`

### 服务接口

```protobuf
service GameService {
    // 发送游戏消息（单向）
    rpc SendGameMessage (GameMessage) returns (GameResponse);
    
    // 心跳保持（双向流）
    rpc Heartbeat (stream HeartbeatRequest) returns (stream HeartbeatResponse);
}
```

### 消息类型

- `GameMessage` - 游戏消息（gateId, playerId, gameId, msgType, body）
- `GameResponse` - 响应（code, message, timestamp）
- `HeartbeatRequest/Response` - 心跳

## 编译 Proto

```bash
cd GateDemo
mvn clean compile
```

生成的代码位置：
- `gate-service/target/generated-sources/protobuf/`
- `game-service/target/generated-sources/protobuf/`

## 配置

### Gate 服务配置（application.yml）

```yaml
gate:
  id: gate-01
  port: 8888
  game:
    host: localhost
    port: 9090  # gRPC 端口
```

### Game 服务配置（application.yml）

```yaml
server:
  port: 8081  # HTTP 端口（可选）

grpc:
  port: 9090  # gRPC 监听端口
```

## 使用示例

### Gate 服务发送消息

```java
@Autowired
private GameGrpcClient gameGrpcClient;

// 发送游戏消息
boolean success = gameGrpcClient.sendGameMessage(
    playerId,      // 玩家 ID
    gameId,        // 游戏 ID
    "battle.move", // 消息类型
    1,             // 序列号
    body           // 消息体（Java 对象）
);
```

### Game 服务接收消息

```java
// GameGrpcServer 自动处理 gRPC 请求
// GameMessageHandler.handleGameMessage() 被调用
```

## 性能优化建议

1. **连接池** - 多 Gate 实例时，Game 服务应配置连接数限制
2. **负载均衡** - 多 Game 实例时，使用 gRPC 内置负载均衡
3. **超时设置** - 配置合理的超时时间（默认 10 秒）
4. **心跳间隔** - 建议 30 秒一次心跳

## 调试

### 查看连接状态

```bash
# Gate 服务日志
grep "gRPC" gate-service/logs/app.log

# Game 服务日志
grep "gRPC" game-service/logs/app.log
```

### 性能测试

```bash
# 使用 grpcurl 测试
grpcurl -plaintext localhost:9090 gameservice.GameService/SendGameMessage
```

## 迁移步骤

1. 更新 `pom.xml` 添加 gRPC 依赖
2. 编译 proto 文件生成代码
3. Gate 服务：`GameHttpClient` → `GameGrpcClient`
4. Game 服务：添加 `GameGrpcServer`
5. 更新 `GameMessageHandler` 支持 gRPC 调用
6. 测试验证
7. 切换流量

## 故障排查

### 连接失败

```
io.grpc.StatusRuntimeException: UNAVAILABLE: Connection refused
```

**解决：** 检查 Game 服务 gRPC 端口是否启动（默认 9090）

### 序列化错误

```
io.grpc.StatusRuntimeException: INVALID_ARGUMENT
```

**解决：** 检查 proto 定义与代码是否匹配，重新编译 proto

### 心跳超时

```
io.grpc.StatusRuntimeException: DEADLINE_EXCEEDED
```

**解决：** 检查网络延迟，调整超时配置

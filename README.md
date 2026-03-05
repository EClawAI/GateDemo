# GateDemo - 无状态游戏网关服务演示 (Java 实现)

> 基于 Redis Stream 实现消息可靠投递的无状态 Gate 服务架构

## 📋 项目说明

本项目演示了如何使用 Redis Stream 实现无状态游戏 Gate 服务，支持：

- ✅ Gate 服务无状态化
- ✅ 玩家切换 Gate 时消息不丢失
- ✅ Gate/Game 水平扩展
- ✅ 消息可靠投递（至少一次）

## 🏗️ 架构设计

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              整体架构                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│   Player ←→ Gate(无状态) ←→ Redis Stream ←→ Game(有状态)                   │
│                                                                             │
│   下行：Game → 查询 Player-Gate 映射 → 写入 Gate Stream → Gate 消费 → Player │
│   上行：Player → Gate → 写入 Game Stream → Game 消费 → 处理                  │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## 📁 项目结构

```
GateDemo/
├── pom.xml                      # Maven 父 POM
├── docker-compose.yml           # Docker 编排
├── gate-service/                # Gate 网关服务 (Spring Boot)
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../gate/
│       │   ├── GateServiceApplication.java
│       │   ├── config/          # 配置类
│       │   ├── handler/        # WebSocket 处理器
│       │   ├── model/          # 数据模型
│       │   └── service/        # 业务服务
│       └── resources/
│           └── application.yml
├── game-service/                # Game 游戏服务 (Spring Boot)
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../game/
│       │   ├── GameServiceApplication.java
│       │   ├── config/
│       │   ├── controller/     # REST API
│       │   ├── model/
│       │   └── service/
│       └── resources/
│           └── application.yml
└── player-client/               # 玩家客户端 (Spring Boot)
    ├── pom.xml
    └── src/main/
        ├── java/.../client/
        │   ├── PlayerClientApplication.java
        │   ├── config/
        │   └── service/
        └── resources/
            └── application.yml
```

## 🚀 快速开始

### 1. 启动 Redis

```bash
docker-compose up -d redis
```

### 2. 编译项目

```bash
mvn clean package -DskipTests
```

### 3. 启动 Gate 服务

```bash
cd gate-service
java -jar target/gate-service-1.0.0.jar
# 或通过 IDE 运行 GateServiceApplication
```

### 4. 启动 Game 服务

```bash
cd game-service
java -jar target/game-service-1.0.0.jar
# 或通过 IDE 运行 GameServiceApplication
```

### 5. 启动 Player 客户端

```bash
cd player-client
java -jar target/player-client-1.0.0.jar --player.player-id=100001
```

### 6. 使用 Docker Compose 启动所有服务

```bash
docker-compose up --build
```

## ⚙️ 配置说明

### Gate 服务配置 (gate-service/src/main/resources/application.yml)

```yaml
server:
  port: 8080

gate:
  id: gate-01
  host: 0.0.0.0
  port: 8888
  redis:
    host: localhost
    port: 6379
    stream:
      consumer-group: gate-01-cluster
      block-ms: 5000
      count: 100
  player:
    heartbeat-interval: 60
    map-ttl: 300
```

### Game 服务配置 (game-service/src/main/resources/application.yml)

```yaml
server:
  port: 8081

game:
  id: game-1001
  redis:
    host: localhost
    port: 6379
    stream:
      consumer-group: game-1001-cluster
```

### Player 客户端配置 (player-client/src/main/resources/application.yml)

```yaml
player:
  player-id: 100001
  host: localhost
  port: 8888
  heartbeat-interval: 30
```

## 📊 API 说明

### Game 服务 REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | /api/game/send/{playerId} | 发送测试消息给玩家 |
| POST | /api/game/send/{playerId}/custom | 发送自定义消息 |
| GET | /api/game/health | 健康检查 |

### WebSocket 消息协议

**客户端 → Gate (WebSocket)**

```json
// 认证
{"type": "auth", "player_id": 100001}

// 心跳
{"type": "heartbeat", "player_id": 100001}

// 游戏消息
{"type": "game_msg", "player_id": 100001, "game_id": 1001, "msg_type": "battle.move", "seq": 123, "body": {...}}
```

**Gate → 客户端 (WebSocket)**

```json
// 认证响应
{"type": "auth_ack", "player_id": 100001, "timestamp": 1234567890}

// 心跳响应
{"type": "heartbeat_ack", "player_id": 100001, "timestamp": 1234567890}

// 游戏消息
{"seq": 123, "msg_type": "battle.update", "body": {...}, "timestamp": 1234567890}
```

## 🔧 核心设计

### Player-Gate 映射表

```redis
# Key: player:gate:{player_id}
# Value: {gate_id}
# TTL: 300 秒

SET player:gate:100001 gate-01 EX 300
GET player:gate:100001
```

### 下行 Stream（Gate 级别）

```redis
# Key: stream:down:gate:{gate_id}
# 每个 Gate 一个独立 Stream

XADD stream:down:gate:01 * player_id 100001 msg_type battle.update body {...}
XREADGROUP GROUP gate-01-cluster gate-01-instance-a STREAMS stream:down:gate:01 >
XACK stream:down:gate:01 gate-01-cluster {message_id}
```

### 上行 Stream（Game 级别）

```redis
# Key: stream:up:game:{game_id}
# 每个 Game 一个 Stream，所有 Gate 写入

XADD stream:up:game:1001 * gate_id gate-01 player_id 100001 msg_type battle.move
XREADGROUP GROUP game-1001-cluster game-1001-instance-a STREAMS stream:up:game:1001 >
```

## 🧪 测试

### 运行单元测试

```bash
mvn test
```

### 运行集成测试

```bash
mvn verify
```

## 🎯 技术栈

| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 3.2 |
| WebSocket | Spring WebSocket |
| Redis | Lettuce (Reactive) |
| 构建 | Maven |
| Java | JDK 17+ |

## 📝 注意事项

1. **Redis 要求**: Redis 6.0+（支持 Stream Consumer Group）
2. **Java 版本**: JDK 17+
3. **网络要求**: Gate/Game/Redis 之间需要低延迟网络
4. **生产建议**: Redis 集群部署，开启持久化

## 🔗 相关文档

- [无状态 Gate 服务设计方案](https://github.com/EClawAI/AIPlans/blob/main/docs/gate-service-design.md)

---

**License**: MIT  
**Author**: clawAI  
**Version**: 1.0.0 (Java Implementation)
# GateDemo - 无状态游戏网关服务

> 基于 gRPC + Redis 的游戏 Gate 服务架构，支持多 Gate 实例水平扩展、服务发现与离线消息缓存

## 架构概览

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                              GateDemo 整体架构                                    │
├──────────────────────────────────────────────────────────────────────────────────┤
│                                                                                  │
│   Player(WebSocket) ──→ Gate(无状态) ──gRPC──→ Game(有状态)                      │
│                              │                     │                             │
│                              ├── Redis ←───────────┤                             │
│                              │   (会话/发现/离线消息)                              │
│                              │                                                   │
│   Center(配置/版本) ←── HTTP ──── Player(初始化)                                  │
│   Login(认证/路由)  ←── HTTP ──── Player(登录)                                    │
│                              │                                                   │
│   上行：Player → Gate → gRPC Bidirectional Stream → Game → 处理                  │
│   下行：Game → gRPC Stream → Gate → WebSocket → Player                           │
│   离线：Game → Redis 离线队列 → 玩家上线时推送                                     │
│                                                                                  │
└──────────────────────────────────────────────────────────────────────────────────┘
```

## 五服务职责

| 服务 | 端口 | 职责 |
|------|------|------|
| **gate-service** | 8888 (WebSocket) / 8890 (Health HTTP) | 无状态网关，管理客户端连接，通过 gRPC 双向流转发消息到 Game |
| **game-service** | 9090 (gRPC) | 有状态游戏逻辑，处理玩家消息，通过 Redis 注册服务状态 |
| **login-service** | 9086 (HTTP) | 玩家认证、Token 生成、游戏服推荐与路由 |
| **center-service** | 9085 (HTTP) | 版本检查、公告下发、SDK 配置 |
| **player-client** | — | 模拟客户端，WebSocket 连接 Gate 并发送消息 |

## 技术栈

| 组件 | 技术 |
|------|------|
| 语言 | Java 17 |
| Gate 网络 | Netty (WebSocket + 自定义健康检查 HTTP) |
| 服务间通信 | gRPC (Protobuf, 双向流) |
| 服务发现 | Redis (Game 心跳注册，Gate 订阅) |
| 离线消息 | Redis (队列缓存) |
| 会话管理 | Redis |
| 连接池 | GameGrpcClientPool (Netty/gRPC ManagedChannel) |
| 认证/路由 | Spring Boot (login-service, center-service) |
| 构建 | Maven |
| 容器 | Docker Compose |

## 项目结构

```
GateDemo/
├── pom.xml                      # Maven 父 POM
├── docker-compose.yml           # Docker 编排（5 服务 + Redis）
├── proto/                       # Protobuf 定义
│   └── src/main/proto/
│       └── game_service.proto
├── gate-service/                # Gate 网关服务 (Netty + gRPC)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/.../gate/
│       ├── GateServiceApplication.java
│       ├── config/              # GateConfig 配置
│       ├── handler/             # WebSocket 处理器
│       ├── grpc/                # gRPC 客户端池、心跳管理
│       ├── health/              # Netty HTTP 健康检查服务
│       ├── model/               # 数据模型
│       └── service/             # 业务服务（发现、会话、离线消息）
├── game-service/                # Game 游戏服务 (gRPC Server)
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/.../game/
│       ├── GameServiceApplication.java
│       ├── config/              # 配置
│       ├── grpc/                # gRPC Server + Health Protocol
│       ├── model/               # 数据模型
│       └── service/             # 业务服务
├── login-service/               # 登录认证服务
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/.../login/
│       ├── LoginServiceApplication.java
│       ├── config/              # 配置
│       ├── controller/          # REST API + 健康检查
│       └── service/             # 认证与路由
├── center-service/              # 中心配置服务
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/.../center/
│       ├── CenterServiceApplication.java
│       ├── config/              # 配置
│       └── controller/          # REST API + 健康检查
├── player-client/               # 模拟玩家客户端
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/.../client/
├── docs/                        # 项目文档
│   ├── 开发计划清单.md
│   └── 提案说明文档.md
└── openspec/                    # Spec-driven 变更管理
```

## 快速开始

### 前置条件

- JDK 17+
- Maven 3.8+
- Docker & Docker Compose

### 方式一：Docker Compose 一键启动

```bash
# 编译所有模块
mvn clean package -DskipTests

# 启动全部服务
docker-compose up --build
```

启动后服务可用状态：

| 服务 | 地址 | 健康检查 |
|------|------|----------|
| Redis | localhost:6379 | `redis-cli ping` |
| center-service | http://localhost:9085 | http://localhost:9085/health |
| login-service | http://localhost:9086 | http://localhost:9086/health |
| game-1001 (gRPC) | localhost:9090 | gRPC Health Protocol |
| gate-01 (WebSocket) | ws://localhost:8888 | http://localhost:8890/health |
| gate-02 (WebSocket) | ws://localhost:8889 | http://localhost:8891/health |

### 方式二：本地逐服务启动

```bash
# 1. 启动 Redis
docker run -d -p 6379:6379 redis:7-alpine

# 2. 启动 center-service
cd center-service && mvn spring-boot:run

# 3. 启动 login-service
cd login-service && mvn spring-boot:run

# 4. 启动 game-service
cd game-service && java -jar target/game-service-1.0.0.jar

# 5. 启动 gate-service
cd gate-service && java -jar target/gate-service-1.0.0.jar

# 6. 启动模拟客户端
cd player-client && java -jar target/player-client-1.0.0.jar --player.player-id=100001
```

## 环境变量

### gate-service

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `GATE_ID` | gate-01 | Gate 实例标识 |
| `GATE_PORT` | 8888 | WebSocket 监听端口 |
| `HEALTH_PORT` | 8890 | 健康检查 HTTP 端口 |
| `GAME_1001_HOST` | localhost | Game 1001 gRPC 主机 |
| `GAME_1001_PORT` | 9090 | Game 1001 gRPC 端口 |
| `REDIS_HOST` | localhost | Redis 主机 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `REDIS_PASSWORD` | redistest | Redis 密码 |
| `GRPC_KEEP_ALIVE_TIME` | 30 | gRPC Keep-alive 间隔 (秒) |
| `GRPC_KEEP_ALIVE_TIMEOUT` | 10 | gRPC Keep-alive 超时 (秒) |
| `GRPC_RECONNECT_DELAY` | 5000 | gRPC 重连延迟 (毫秒) |
| `GRPC_HEARTBEAT_INTERVAL` | 30000 | gRPC 心跳间隔 (毫秒) |

### game-service

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `GAME_ID` | 1001 | Game 服务标识 |
| `GRPC_PORT` | 9090 | gRPC 监听端口 |
| `REDIS_HOST` | localhost | Redis 主机 |
| `REDIS_PORT` | 6379 | Redis 端口 |
| `REDIS_PASSWORD` | redistest | Redis 密码 |

### login-service

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SERVER_PORT` | 9086 | HTTP 监听端口 |
| `SPRING_DATA_REDIS_HOST` | localhost | Redis 主机 |
| `SPRING_DATA_REDIS_PORT` | 6379 | Redis 端口 |
| `SPRING_DATA_REDIS_PASSWORD` | redistest | Redis 密码 |

### center-service

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SERVER_PORT` | 9085 | HTTP 监听端口 |

## 健康检查

| 服务 | 方式 | 端点 | 检查内容 |
|------|------|------|----------|
| gate-service | Netty HTTP | `GET /health`, `GET /ready` | Redis 连通性, gRPC 连接池状态 |
| game-service | gRPC Health Protocol | `grpc_health_probe -addr=:9090` | gRPC 服务可用性 |
| login-service | Spring MVC | `GET /health` | Redis 连通性 |
| center-service | Spring MVC | `GET /health` | 服务存活 |
| Redis | redis-cli | `redis-cli ping` | Redis 进程存活 |

## 消息协议

### 客户端 → Gate (WebSocket)

```json
{"type": "auth", "player_id": 100001}

{"type": "heartbeat", "player_id": 100001}

{"type": "game_msg", "player_id": 100001, "game_id": 1001, "msg_type": "battle.move", "seq": 123, "body": {}}
```

### Gate → 客户端 (WebSocket)

```json
{"type": "auth_ack", "player_id": 100001, "timestamp": 1234567890}

{"type": "heartbeat_ack", "player_id": 100001, "timestamp": 1234567890}

{"seq": 123, "msg_type": "battle.update", "body": {}, "timestamp": 1234567890}
```

## CI/CD

项目使用 GitHub Actions 实现持续集成与部署。

### CI（持续集成）

触发条件：PR 或推送到 `main`/`master`/`feature/**` 分支

- Maven 多模块构建与测试 (`mvn clean verify`)
- JaCoCo 覆盖率报告自动生成并上传为 artifact
- 测试结果上传为 artifact

### Deploy（持续部署）

触发条件：推送到 `main`/`master` 分支

- 构建通过后，为 gate-service、game-service、login-service、center-service 分别构建 Docker 镜像
- 镜像推送到 GitHub Container Registry (ghcr.io)
- 标签：commit SHA + `latest`

### 本地生成覆盖率报告

```bash
mvn clean verify
# 报告位于各模块 target/site/jacoco/index.html
```

## 相关文档

- [gRPC 连接池设计](GRPC_POOL_README.md)
- [开发计划清单](docs/开发计划清单.md)
- [提案说明文档](docs/提案说明文档.md)

---

**License**: MIT
**Author**: clawAI

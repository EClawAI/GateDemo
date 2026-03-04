# GateDemo - 无状态游戏网关服务演示

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
├── README.md           # 项目说明
├── docker-compose.yml  # Docker 编排
├── requirements.txt    # Python 依赖
├── config/
│   └── config.yaml     # 配置文件
├── gate/
│   ├── __init__.py
│   ├── main.py         # Gate 服务入口
│   ├── connection.py   # 玩家连接管理
│   └── consumer.py     # Redis Stream 消费者
├── game/
│   ├── __init__.py
│   ├── main.py         # Game 服务入口
│   └── sender.py       # 消息发送器
├── player_client/
│   └── client.py       # 玩家模拟客户端
└── scripts/
    └── init_redis.lua  # Redis 初始化脚本
```

## 🚀 快速开始

### 1. 启动 Redis

```bash
docker-compose up -d redis
```

### 2. 启动 Gate 服务

```bash
cd gate
python main.py
```

### 3. 启动 Game 服务

```bash
cd game
python main.py
```

### 4. 运行玩家客户端

```bash
cd player_client
python client.py
```

## 📊 核心设计

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

## 🔧 配置说明

### Gate 配置

```yaml
gate:
  id: gate-01
  host: 0.0.0.0
  port: 8888
  redis:
    host: localhost
    port: 6379
    stream:
      consumer_group: gate-01-cluster
      block_ms: 5000
      count: 100
  player:
    heartbeat_interval: 60
    map_ttl: 300
```

### Game 配置

```yaml
game:
  id: game-1001
  redis:
    host: localhost
    port: 6379
    stream:
      consumer_group: game-1001-cluster
```

## 📈 测试场景

### 测试套件

GateDemo 包含完整的测试套件，覆盖以下场景：

| 测试场景 | 说明 | 状态 |
|----------|------|------|
| 基础连接测试 | 验证玩家连接和 Player-Gate 映射 | ✅ |
| 断线重连 - 原 Gate 可用 | 玩家断线后重连到同一 Gate | ✅ |
| 断线重连 - 原 Gate 不可用 | Gate 宕机后玩家重连到新 Gate | ✅ |
| 消息投递失败处理 | 验证 Gate→Player 投递失败后的处理 | ✅ |
| 消息重复处理 | 验证重复消息的处理机制 | ✅ |
| 多玩家并发测试 | 验证多玩家并发连接和消息 | ✅ |

### 运行测试

```bash
# 安装依赖
pip install -r requirements.txt

# 启动 Redis
docker-compose up -d redis

# 启动 Gate 服务
python gate/main.py &

# 启动 Game 服务
python game/main.py &

# 运行完整测试
bash run_tests.sh run

# 快速测试
bash run_tests.sh quick

# 压力测试（100 并发）
bash run_tests.sh stress 100

# 查看测试报告
bash run_tests.sh report
```

### 测试报告示例

```
╔════════════════════════════════════════════════════════════╗
║                    测试报告汇总                             ║
╠════════════════════════════════════════════════════════════╣
║  总计：  6  |  通过：  6  |  失败：  0  |  跳过：  0
║  耗时：12.34 秒
║  成功率：100.0%
╚════════════════════════════════════════════════════════════╝
```

### 手动测试

#### 1. 基础消息投递

```bash
# 启动 Player 客户端
cd player_client
python client.py --player-id 100001 --gate-port 8888

# 另一个终端：Game 发送测试消息
python game/main.py --send-test --player-id 100001
```

#### 2. Gate 故障切换

```bash
# Player 连接 Gate-01
python client.py --player-id 100001 --gate-port 8888

# 停止 Gate-01 (Ctrl+C)

# Player 重连 Gate-02
python client.py --player-id 100001 --gate-port 8889

# Game 发送消息，验证消息投递到新 Gate
```

## 🎯 关键特性

| 特性 | 实现方式 |
|------|----------|
| 无状态 Gate | 消息持久化在 Redis，Gate 不持有状态 |
| 消息可靠 | Redis Stream + ACK 机制 |
| 玩家重连 | Player-Gate 映射表 + Stream Pending |
| 水平扩展 | Consumer Group 负载均衡 |

## 📝 注意事项

1. **Redis 要求**: Redis 6.0+（支持 Stream Consumer Group）
2. **网络要求**: Gate/Game/Redis 之间需要低延迟网络
3. **生产建议**: Redis 集群部署，开启持久化

## 🔗 相关文档

- [无状态 Gate 服务设计方案](https://github.com/EClawAI/AIPlans/blob/main/docs/gate-service-design.md)

---

**License**: MIT  
**Author**: clawAI

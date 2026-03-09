## Context

当前Gate服务与Game服务之间的gRPC连接是静态的

问题场景：
1. **K8s扩缩容**：Gate Pod变化时连接问题
2. **Game扩缩容**：Gate无法感知Game变化

**新问题**：
当Gate只连接部分Game时，玩家如何路由？

## Architecture - EnterServer

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        ZooKeeper                                  │
│  /gate/{id} ─────────────── /game/{id} ─────────────── /enter │
└─────────────────────────────────────────────────────────────────┘
        ↑Watch                   ↑Watch
        │                        │
   ┌────┴────┐            ┌─────┴─────┐
   │  Gate   │            │  Enter    │
   │ (多个)   │            │  Server   │
   └─────────┘            └───────────┘
        │                        │
        │  gRPC                  │
        └────────┬───────────────┘
                 │
          ┌──────┴──────┐
          │  Game Server │
          │   (多个)     │
          └─────────────┘
```

### EnterServer职责

1. **玩家登录入口**：处理首次登录请求
2. **选服逻辑**：返回玩家应该连接的Game服务器
3. **会话管理**：记录玩家的GameServer映射
4. **负载均衡**：选择负载最低的Game

### 玩家登录流程

```
玩家客户端
     ↓
连接EnterServer (或通过DNS/LB)
     ↓
发送 login 请求 (playerId, deviceId)
     ↓
EnterServer查询玩家数据
     ├── 首次登录 → 选择负载最低的Game → 返回Game地址
     └── 已创建角色 → 返回上次登录的Game地址
     ↓
玩家连接Gate → Gate转发到对应Game
```

### EnterServer数据模型

```java
// 玩家会话
class PlayerSession {
    Long playerId;
    Integer gameId;           // 当前所在的Game
    String gameHost;          // Game地址
    Integer gamePort;
    Long lastLoginTime;
    Integer onlineStatus;     // 0:离线 1:在线
}
```

### 路由决策

| 场景 | 决策 | 说明 |
|------|------|------|
| 首次登录 | 选择负载最低的Game | 负载均衡 |
| 已创角 | 返回上次Game | 会话保持 |
| 上次Game已下线 | 选择其他Game | 故障转移 |
| 指定服登录 | 验证并返回 | 玩家主动选服 |

### EnterServer与Game的交互

EnterServer需要知道Game的负载情况：

```java
// 从ZooKeeper获取Game列表
List<GameInstance> games = zkService.getGameInstances();

// 负载信息来源：
// 1. ZooKeeper节点数据 (gameId:host:port:onlineCount)
// 2. Game定期上报负载到Redis/ZK
// 3. Gate上报连接数
```

### Gate设计调整

由于EnterServer解决了路由问题，Gate可以简化：

```
Gate设计：
- Gate连接所有Game（或按需连接）
- EnterServer返回gameId后，Gate建立/复用连接到该Game的连接
- Gate只负责消息转发
```

### 数据存储

```yaml
存储方案：
- Redis: 玩家会话数据 (快速读写)
  - key: player:{playerId}
  - value: {gameId, gameHost, gamePort, lastLoginTime}
  
- MySQL: 玩家基础数据
  - player_info: 玩家ID、名称、等级等
  - player_game_record: 玩家游戏记录
```

### 配置示例

```yaml
enter:
  server:
    id: enter-001
    port: 8887
  redis:
    host: localhost
    port: 6379
  game:
    # Game服务发现
    discovery-enabled: true
  routing:
    # 路由策略
    strategy: load-balance  # 负载均衡
    # 会话保持
    session-timeout: 3600
```

## 实施

### 模块划分

```
项目结构：
├── gate-service/     # 网关服务
├── enter-service/   # 入口服务 (新增)
└── game-service/    # 游戏服务
```

### EnterService核心功能

1. **LoginHandler**: 处理登录请求
2. **GameRouter**: 路由决策
3. **SessionManager**: 会话管理
4. **ServiceDiscovery**: Game服务发现

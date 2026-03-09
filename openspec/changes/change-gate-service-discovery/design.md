## Context

当前Gate服务与Game服务之间的gRPC连接是静态的：

```
Gate启动 → 读取配置 → 与配置的Game建立连接 → 保持长连接
```

问题场景：
1. **K8s扩缩容**：Gate Pod从3个增加到5个，新Pod需要建立与所有Game的连接
2. **K8s滚动更新**：旧Gate Pod关闭时断开所有连接，新Pod重新建立
3. **Game扩缩容**：Game服务扩缩容时，Gate无法感知
4. **大规模场景**：Game节点超过10k时，现有架构无法支撑

**负载均衡问题**：
当每个Gate只连接部分Game时：
- 玩家通过DNS/LB连接Gate（玩家知道Gate地址）
- 玩家登录时，Gate需要知道将玩家路由到哪个Game
- 如果Gate不管理所有Game，如何确定目标Game？

## Goals / Non-Goals

**Goals:**
- Gate实现无状态，支持K8s水平扩缩容
- Gate能够动态感知Game服务的上线/下线
- Game能够动态感知Gate服务的上线/下线
- 支持服务健康检测
- 支持大规模Game节点（10k+）
- 实现Gate层负载均衡，支持玩家路由

**Non-Goals:**
- 不改变现有的gRPC通信协议
- 不改变消息的业务语义

## Decisions

### Decision 1: 选择服务发现方案

**选择**: ZooKeeper

**理由**:
- 成熟稳定，业界广泛使用
- 支持临时节点，自动删除
- 支持Watch机制，实时感知变化
- 与Spring Cloud集成良好

### Decision 2: 服务注册方式

**Gate注册**:
- 临时节点：`/gate/{gateId}`
- 节点内容：`{host}:{port}:{healthy}`
- 心跳维持：每10秒更新一次

**Game注册**:
- 临时节点：`/game/{gameId}`
- 节点内容：`{host}:{port}:{healthy}`

### Decision 3: 大规模场景设计（10k+ Game节点）

**核心原则**：单Gate不连接所有Game，采用分片/路由策略

#### 3.1 连接数限制

单个Gate最多连接 `N` 个Game节点（可配置，默认100）

#### 3.2 分片策略

采用一致性哈希分片：
- 每个Gate根据 `hash(gateId) % gameCount` 确定负责的Game分片
- 同一Game始终由固定的几个Gate服务
- 减少连接抖动

#### 3.3 滚动更新保护

Gate滚动更新时，采用渐进式重连

### Decision 4: 路由策略（重点）

**问题**：玩家登录时，Gate如何知道将玩家路由到哪个Game？

**解决方案**：一致性哈希

#### 4.1 玩家→Game路由

```
玩家ID ──hash──> Game节点
hash(playerId) % gameCount = targetGameIndex
```

- **一致性哈希**：同一玩家始终路由到同一Game（会话保持）
- **Gate本地计算**：Gate根据playerId计算目标Game
- **无需外部依赖**：不需要查询路由表

#### 4.2 路由流程

```
玩家连接Gate
    ↓
玩家发送登录消息 (playerId=12345)
    ↓
Gate计算: hash(12345) % 10000 = 5678
    ↓
Gate查找gameId=5678的连接
    ↓
通过gRPC转发到对应Game
```

#### 4.3 分片映射表

Gate维护本地分片映射表：

```java
// 分片映射表
Map<Integer, GameConnection> shardingMap;  // gameId -> Connection

// 计算目标Game
int targetGameId = (int) (Math.abs(playerId.hashCode()) % totalGameCount);
```

#### 4.4 Game服务发现更新

当Game列表变化时：
- ZooKeeper通知Gate
- Gate更新本地分片映射表
- 使用渐进式更新，避免瞬时压力

#### 4.5 路由失败处理

- **目标Game不可用**：尝试连接其他健康Game
- **连接池满**：返回错误或重试

### Decision 5: 连接管理策略

**Gate端**:
- 启动时：从ZooKeeper发现所有Game，建立分片连接
- Watch事件：Game新增 → 检查分片 → 决定是否连接
- 定时检查：每30秒检查连接健康

**Game端**:
- 启动时：发现所有Gate，允许连接
- Watch事件：Gate新增 → 允许连接；Gate删除 → 断开连接

### Decision 6: 服务健康检测

**方案**: 应用层心跳 + ZooKeeper临时节点TTL

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    ZooKeeper                             │
│  /gate/{id} ─────────── /game/{id}                     │
│  /gate/{id} ─────────── /game/{id}                     │
└─────────────────────────────────────────────────────────┘
        ↑Watch              ↑Watch
        │                   │
   ┌────┴────┐         ┌────┴────┐
   │ Gate    │         │ Game    │
   │         │◄──────►│ (分片)  │
   │ 路由表  │         │         │
   └─────────┘         └─────────┘
   
玩家请求流程：
┌──────┐     ┌──────┐     ┌──────────┐     ┌──────┐
│玩家  │────>│ Gate │────>│ 一致性   │────>│Game  │
│      │     │      │     │ 哈希路由  │     │      │
└──────┘     └──────┘     └──────────┘     └──────┘
```

## 路由算法详解

### 一致性哈希

```java
public class RoutingStrategy {
    
    // 使用一致性哈希确定目标Game
    public int routeToGame(long playerId, List<GameInstance> games) {
        if (games.isEmpty()) {
            throw new RuntimeException("No available games");
        }
        
        // 一致性哈希
        int index = Math.abs(Long.hashCode(playerId)) % games.size();
        return games.get(index).getGameId();
    }
    
    // 或使用 Ketama 一致性哈希（推荐）
    // 支持虚拟节点，减少数据倾斜
}
```

### 分片映射表更新

```java
public void updateShardingMap(List<GameInstance> games) {
    // 渐进式更新，避免瞬时压力
    for (int i = 0; i < games.size(); i += BATCH_SIZE) {
        // 每次更新一批
        updateBatch(games.subList(i, i + BATCH_SIZE));
        // 等待一段时间
        sleep(INTERVAL);
    }
}
```

## 配置示例

```yaml
gate:
  id: gate-001
  zookeeper:
    host: localhost:2181
  grpc:
    max-connections: 100
    reconnect-batch-size: 10
    reconnect-interval: 5000
  routing:
    # 路由策略
    strategy: consistent-hash  # 一致性哈希
    virtual-nodes: 150        # 虚拟节点数
    # 分片配置
    sharding-enabled: true
    # 重试配置
    max-retries: 3
    retry-interval: 1000
```

## Risks / Trade-offs

1. **[风险]** ZooKeeper单点故障
   - **解决**: ZooKeeper集群部署（3节点以上）

2. **[风险]** 网络抖动导致频繁重连
   - **解决**: 添加重连冷却时间

3. **[风险]** 连接数过多
   - **解决**: 限制单个Gate最多连接数

4. **[风险]** 10k+ Game节点导致ZooKeeper压力
   - **解决**: 本地缓存 + 分片策略 + 渐进式更新

5. **[风险]** 负载不均
   - **解决**: 虚拟节点一致性哈希

6. **[风险]** 玩家路由到已下线的Game
   - **解决**: 健康检测 + 故障转移

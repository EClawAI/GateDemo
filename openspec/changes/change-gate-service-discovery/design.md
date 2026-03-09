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

## Goals / Non-Goals

**Goals:**
- Gate实现无状态，支持K8s水平扩缩容
- Gate能够动态感知Game服务的上线/下线
- Game能够动态感知Gate服务的上线/下线
- 支持服务健康检测
- 支持大规模Game节点（10k+）

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

**备选方案**:
- Nacos: 更适合Spring Cloud生态，但需要额外部署
- Eureka: 不支持临时节点

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

单个Gate最多连接 `N` 个Game节点（可配置，默认100）：
```yaml
gate:
  grpc:
    max-connections: 100  # 单Gate最大连接数
```

#### 3.2 分片策略

采用一致性哈希分片：
- 每个Gate根据 `hash(gateId) % gameCount` 确定负责的Game分片
- 同一Game始终由固定的几个Gate服务
- 减少连接抖动

#### 3.3 滚动更新保护

Gate滚动更新时，采用渐进式重连：
- 每次最多重连 `M` 个Game（可配置，默认10）
- 重连间隔 `T` 秒（可配置，默认5秒）
- 避免瞬时大量ZooKeeper操作

```yaml
gate:
  grpc:
    reconnect-batch-size: 10   # 每次重连数量
    reconnect-interval: 5000   # 重连间隔(ms)
```

#### 3.4 ZooKeeper压力控制

- **本地缓存**：Gate本地缓存Game服务列表，减少ZooKeeper查询
- **批量处理**：ZooKeeper事件批量处理，避免频繁触发
- **限流**：对ZooKeeper操作进行限流

### Decision 4: 连接管理策略

**Gate端**:
- 启动时：从ZooKeeper发现部分Game（根据分片），建立连接
- Watch事件：Game新增 → 检查是否在负责的分片 → 决定是否连接
- 定时检查：每30秒检查连接健康

**Game端**:
- 启动时：发现所有Gate，允许连接
- Watch事件：Gate新增 → 允许连接；Gate删除 → 断开连接

### Decision 5: 服务健康检测

**方案**: 应用层心跳 + ZooKeeper临时节点TTL

- Gate/Game每10秒向ZooKeeper发送心跳
- 临时节点TTL设置为30秒
- 超过30秒未心跳，节点自动删除

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    ZooKeeper                             │
│  /gate/{id} ─────────── /game/{id}                     │
│  /gate/{id} ─────────── /game/{id}                     │
│  /gate/{id} ─────────── /game/{id}                     │
└─────────────────────────────────────────────────────────┘
        ↑Watch              ↑Watch
        │                   │
   ┌────┴────┐         ┌────┴────┐
   │ Gate-1  │         │ Game-1  │
   │ (连接   │◄──────►│ (100+个) │
   │  部分)  │         │         │
   └─────────┘         └─────────┘
```

**大规模场景下的连接模型**：
- 单Gate只连接100个Game（可配置）
- 使用一致性哈希决定连接哪些Game
- Game故障时，Gate自动切换到其他健康节点

## 配置示例

```yaml
gate:
  id: gate-001
  zookeeper:
    host: localhost:2181
    session-timeout: 30000
  grpc:
    # 连接限制
    max-connections: 100
    # 滚动更新保护
    reconnect-batch-size: 10
    reconnect-interval: 5000
    # 心跳配置
    heartbeat-interval: 10000
    heartbeat-ttl: 30000
  discovery:
    # 分片策略
    sharding-enabled: true
    # 本地缓存
    cache-enabled: true
    cache-refresh-interval: 60000
```

## Risks / Trade-offs

1. **[风险]** ZooKeeper单点故障
   - **解决**: ZooKeeper集群部署（3节点以上）

2. **[风险]** 网络抖动导致频繁重连
   - **解决**: 添加重连冷却时间（可配置）

3. **[风险]** 连接数过多
   - **解决**: 限制单个Gate最多连接数（可配置）

4. **[风险]** 10k+ Game节点导致ZooKeeper压力
   - **解决**: 
     - 本地缓存减少ZooKeeper查询
     - 分片策略减少单Gate连接数
     - 渐进式重连避免瞬时压力

5. **[风险]** Gate滚动更新时连接抖动
   - **解决**: 
     - 渐进式重连
     - 一致性哈希分片保证同一Game由固定Gate服务

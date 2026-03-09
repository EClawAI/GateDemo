## Context

当前Gate服务与Game服务之间的gRPC连接是静态的：

```
Gate启动 → 读取配置 → 与配置的Game建立连接 → 保持长连接
```

问题场景：
1. **K8s扩缩容**：Gate Pod从3个增加到5个，新Pod需要建立与所有Game的连接
2. **K8s滚动更新**：旧Gate Pod关闭时断开所有连接，新Pod重新建立
3. **Game扩缩容**：Game服务扩缩容时，Gate无法感知

## Goals / Non-Goals

**Goals:**
- Gate实现无状态，支持K8s水平扩缩容
- Gate能够动态感知Game服务的上线/下线
- Game能够动态感知Gate服务的上线/下线
- 支持服务健康检测

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

### Decision 3: 连接管理策略

**Gate端**:
- 启动时：发现所有Game，建立连接
- Watch事件：Game新增 → 建立连接；Game删除 → 断开连接
- 定时检查：每30秒检查连接健康

**Game端**:
- 启动时：发现所有Gate，允许连接
- Watch事件：Gate新增 → 允许连接；Gate删除 → 断开连接

### Decision 4: 服务健康检测

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
   │ Gate-2  │◄──────►│ Game-2  │
   │ Gate-3  │         │ Game-3  │
   └─────────┘         └─────────┘
```

## Risks / Trade-offs

1. **[风险]** ZooKeeper单点故障
   - **解决**: ZooKeeper集群部署（3节点以上）

2. **[风险]** 网络抖动导致频繁重连
   - **解决**: 添加重连冷却时间（5秒）

3. **[风险]** 连接数过多
   - **解决**: 限制单个Gate最多连接数（可配置）

## Context

- **当前状态**：Gate 与 Game 之间已有两种 gRPC 连接来源：
  - **GameGrpcClientPool**（配置驱动）：从 `gate.games` 读取 Game 列表，维护 `Map<gameId, GrpcConnection>`，每个连接包含 ManagedChannel、BlockingStub、AsyncStub，支持双向 Stream、心跳（GrpcHeartbeatManager）、Stream 异常时 scheduleReconnect。
  - **GameDiscoveryService**（Redis 服务发现）：当 `gate.discovery.enabled=true` 时，单独维护 `Map<gameId, ManagedChannel>`（gameChannels），通过 Redis 注册/事件动态 connectToGame/disconnectFromGame，并提供 getChannel(gameId)。
- **问题**：存在两套连接管理；Discovery 路径未复用连接池，也未统一健康检查与重连策略。提案要求：Gate 统一维护连接池、连接复用、健康检查、自动重连。
- **约束**：保持现有 gRPC/Stream 协议与 Game 端行为；不改变服务发现协议本身。

## Goals / Non-Goals

**Goals:**
- Gate 侧仅通过**单一连接池**（GameGrpcClientPool）与各 Game 通信，所有取 channel 的调用都走连接池。
- 连接池支持：预创建/复用、超时与池大小配置、健康检查（含定时 Ping/心跳）、失效连接移除与自动重连。
- 当启用服务发现时，Discovery 只负责发现/注销实例，连接的创建与销毁委托给连接池（addConnection/removeConnection），不再维护独立 gameChannels。

**Non-Goals:**
- 不改变 Game 端 gRPC 接口或健康检查协议。
- 不实现跨 Gate 的共享连接池（每 Gate 进程内一个池即可）。
- 不在此 change 中实现 Game 端主动上报健康状态（仅 Gate 侧探测即可）。

## Decisions

1. **统一入口为 GameGrpcClientPool**
   - 所有需要访问 Game gRPC 的代码（含 PlayerService、未来其他调用方）只通过 GameGrpcClientPool 获取连接或发送消息。
   - **理由**：避免双源 truth、重复建连与不一致的超时/重连策略。替代方案（仅用 Discovery 的 channel）会丢失当前池内已有的 Stream/心跳/重连逻辑，故不采纳。

2. **Discovery 与连接池的职责划分**
   - GameDiscoveryService：只负责从 Redis 拉取/监听 Game 实例列表并维护 gameMap；当实例注册/下线时，调用 GameGrpcClientPool.addConnection / removeConnection，不再维护 gameChannels。
   - GameGrpcClientPool：成为唯一持有 ManagedChannel（或 GrpcConnection）的组件；支持由配置（gate.games）初始化，也支持由 Discovery 动态 add/remove。
   - **理由**：连接生命周期与健康检查、重连集中在一处，便于配置与运维。若 Discovery 与配置同时包含同一 gameId，以“先到先得”或配置优先策略在实现中约定即可。

3. **健康检查与失效处理**
   - 沿用现有心跳（GrpcHeartbeatManager + Stream 上的心跳）作为健康探测；心跳失败或 Stream onError 时视为连接不可用。
   - 失效时：移除该连接（removeConnection），并由现有 scheduleReconnect 或 Discovery 的再次注册触发重建。
   - **理由**：不引入新 RPC 类型，与当前 keepAlive/Stream 模型一致。若后续需要更细粒度（如仅关闭 Stream 不关 Channel），可在实现中保留扩展点。

4. **连接池大小与超时**
   - 池模型：每 Game 实例一个 GrpcConnection（单 channel 复用多请求/Stream）。池“大小”即实例数，由配置或 Discovery 决定。
   - 超时：继续使用现有 channel 的 keepAliveTime/keepAliveTimeout 与业务超时；可在 gate 配置中增加可选连接超时/空闲超时项便于调优。
   - **理由**：与当前实现一致，避免过度设计；需要时再增加“每实例多 channel”的池化。

5. **配置与兼容**
   - 保留 `gate.games` 静态配置；当未启用 Discovery 时，仅由此初始化连接池。
   - 当启用 Discovery 时，Discovery 在加载/收到事件时调用池的 addConnection/removeConnection，配置中的 games 可与 Discovery 并存（具体覆盖或合并策略在实现中明确并文档化）。
   - **理由**：兼容现有部署方式，同时支持动态发现。

## Risks / Trade-offs

- **[风险] Discovery 与配置同时管理同一 gameId** → 在实现中约定：例如仅 Discovery 更新动态实例，或配置仅作 bootstrap，由文档与日志明确行为；避免重复 add 导致资源泄漏。
- **[风险] 重连风暴** →  mitigation：保留现有 scheduleReconnect 的简单退避（如 5s），必要时改为指数退避并加最大重试次数。
- **[权衡] 单 channel  per game** → 若某 Game 实例请求量极大，单 channel 可能成为瓶颈；当前按“每实例一连接”实现，后续可再增加“每实例多 channel”的池化。

## Migration Plan

- **实现顺序**：先让 GameDiscoveryService 不再维护 gameChannels，改为调用 GameGrpcClientPool.addConnection/removeConnection；将所有 getChannel(gameId) 的调用方改为经 GameGrpcClientPool 获取或发消息；确认健康检查与重连仅由池与 GrpcHeartbeatManager 负责。
- **部署**：无数据迁移；先部署 Gate，再观察连接数、心跳与重连日志。若 Discovery 与配置并存，建议先在测试环境验证 gameId 冲突策略。
- **回滚**：保留 GameDiscoveryService 中 connectToGame/disconnectFromGame/getChannel 的旧实现到一次发布内，若有问题可快速回退到“Discovery 自管 channel”的开关或回滚部署。

## Open Questions

- 当 `gate.discovery.enabled=true` 且 `gate.games` 也配置了部分 gameId 时，是否允许重叠？若允许，以 Discovery 为准还是以配置为准？建议在实现前与团队约定并写入配置说明。

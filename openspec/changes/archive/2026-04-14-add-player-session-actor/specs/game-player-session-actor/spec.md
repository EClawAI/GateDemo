# game-player-session-actor Specification (delta)

## ADDED Requirements

### Requirement: 进程内按 playerId 至多一个 PlayerSessionActor

`game-service` SHALL 为每个当前 **被至少一条活跃 `streamCommunication` 流占用** 的 **`playerId`** 维护 **至多一个** Typed **`PlayerSessionActor`**，作为该玩家在线会话的 **串行处理边界**。

#### Scenario: 同 player 复用同一会话

- **WHEN** 两条不同流（或同一流多次）向同一 `playerId` 投递上行业务帧
- **THEN** 处理该 `playerId` 的 **`GameMessageDispatcher.dispatch` 调用** 均发生在 **同一** `PlayerSessionActor` 实例的邮箱语义下（顺序处理）

### Requirement: PlayerSessionRegistry 管理创建与流级释放

`game-service` SHALL 提供 **Typed `PlayerSessionRegistry` Actor**，负责：

- 为 **`RouteInbound(streamId, playerId, …)`** 解析或创建对应 `PlayerSessionActor`；
- 维护 **`streamId` 与 `playerId` 的占用关系**，并在收到 **`StreamClosed(streamId)`**（或等价信号）时释放该流关联的所有 `playerId`；
- 当某 `playerId` 的 **占用计数归零** 时 **停止** 对应 `PlayerSessionActor`，避免泄漏。

#### Scenario: 流结束后无会话泄漏

- **WHEN** 某 `streamCommunication` 流已结束且 Registry 已处理 `StreamClosed(streamId)`
- **THEN** 仅被该流独占的玩家会话 Actor 被停止；仍被其他流占用的 `playerId` 会话 **保持**运行

### Requirement: PlayerSession 调用既有 dispatch

`PlayerSessionActor` SHALL 在 **自身 Actor 执行线程**上调用既有 **`GameMessageDispatcher.dispatch(long playerId, int messageId, int seq, byte[] body)`**，其 `playerId` MUST 与该会话绑定的玩家一致。

#### Scenario: 与阶段 2 载荷一致

- **WHEN** `PlayerSessionActor` 处理一条入站帧
- **THEN** 传入 `dispatch` 的参数与 `GameMessage` proto 字段语义一致

### Requirement: 面向 World 的命令类型表（占位）

`design.md` 或本能力规格 SHALL 包含 **「会话 Actor → 世界侧」命令类型表** 的 **初版占位**（可为空列表或枚举占位名），以便阶段 4 起填充 **Region/City** 交互；本变更 **不**要求实现世界 Actor。

#### Scenario: 占位可追溯

- **WHEN** 审阅本归档目录下 `design.md` 或 `specs/game-player-session-actor/spec.md`
- **THEN** 可找到上述命令类型表或明确的「待补充」小节

### Requirement: 监督策略可审计

`design.md` SHALL 说明 **Registry** 对 **PlayerSessionActor** 子级的 **监督（supervision）** 策略（例如失败时 `stop` 或 `restart`）及简要理由。

#### Scenario: 文档与实现对齐

- **WHEN** 代码实现监督行为
- **THEN** 行为与 `design.md` 声明一致或 `design.md` 已更新

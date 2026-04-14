## Why

阶段 2 已将 `StreamCommunication` 上行帧迁出 IO 线程，但 **`GameMessageDispatcher.dispatch` 仍按「每流」串行**，尚未按路线图把 **在线玩家** 作为独立聚合边界。本变更引入 **PlayerSessionActor（每 `playerId` 至多一个 Typed Actor）**，为后续钱包串行、世界侧 Ask（阶段 4–5）提供 **可寻址会话层**。

## What Changes

- 新增 **PlayerSessionRegistry**（Typed Actor）：按 **`playerId`** 创建/复用 **PlayerSessionActor**，并对 **「流 ↔ 玩家」** 做引用计数；**双向流关闭**时释放对应玩家会话，避免 Actor 泄漏。
- **PlayerSessionActor**：在 **自身邮箱线程**上调用既有 **`GameMessageDispatcher.dispatch`**，保持与 **MessageHandlerRegistry** / **PlayerDataManager** 行为一致。
- **StreamIngressBehavior** 调整为 **仅**将 `InboundStreamFrame` 路由到 Registry（不再直接 `dispatch`），仍满足「IO 回调不执行业务」。
- 新增/更新规格：`game-player-session-actor`（新）、`game-grpc-stream-actor-bridge`（桥接路径与 `dispatch` 落点澄清）。

## Capabilities

### New Capabilities

- `game-player-session-actor`：在线玩家会话 Actor 的寻址、生命周期（首帧创建、流结束释放）、向 World 发出的命令类型占位表（初版可为空或枚举占位）。

### Modified Capabilities

- `game-grpc-stream-actor-bridge`：澄清 **`dispatch` 可在 PlayerSessionActor 上执行**（仍为 Typed Actor 执行线程），StreamIngress 可仅转发。

## Impact

- **代码**：`game-service` 下 Pekko 包新增 Registry + PlayerSession；`StreamIngressBehavior`、`GameStreamInboundObserver`、`GameGrpcServer` 装配与测试调整。
- **依赖**：无新 Maven 依赖（沿用既有 `pekko-actor-typed`）。
- **行为**：同一 `playerId` 在进程内 **全局串行**处理上行业务消息（跨多条流若出现则顺序由邮箱保证）；**多流同玩家** 由 refcount 管理，**非目标**场景（如多 Gate 冲突）留待后续 Cluster/产品约束。

## Dependencies

- 归档变更：`2026-04-14-bridge-grpc-stream-to-game-actor-mailbox`、`2026-04-14-integrate-pekko-actor-system-in-game-service`（及 `game-pekko-actor-runtime` 规格）。
- 路线图：`docs/pekko-game-actor-openspec-roadmap.md` 阶段 3。

## Non-Goals（摘要）

- 不实现 Region/City、掠夺 Ask、Cluster Sharding。
- 不改变 Gate–Game **proto**；Unary `SendGameMessage` 仍可不经过 PlayerSession（与阶段 2 TODO 一致）。
- 不在本变更引入完整 **`define-game-actor-message-envelope`**（`correlationId` 等仍为后续 change）。

## Risks

- **多流同玩家**：引用计数错误会导致泄漏或过早停止；需单测覆盖。
- **全局 `playerId` 会话**：若未来需「每连接一会话」需改路由策略；本阶段与路线图「在线一人一 Actor」一致。

## Why

当前实现采用 **`WorldRegistryBehavior` → `RegionBehavior` → `CityBehavior`** 三级懒创建，**Region 不持业务数据**、**City 持本城占位缓存**；与分服 SLG 下「**粗聚合 + 单沙盘写入边界**」的目标相比，**层级偏多、世界侧写入边界分散在多城 Actor**，增加异步协作与持久化边界说明成本。

本变更依据讨论结论，将世界侧收敛为 **「每服 × 每玩法模式一张大地图」单一 Typed Actor（沙盘聚合）**，并明确 **玩家聚合**、**联盟聚合** 与 **无状态 Worker** 的职责，使 **顺序业务主要在少数邮箱内完成**，重计算通过 **Worker 池** 完成且 **不持有权威状态**。

## What Changes

### 目标架构（逻辑）

| 聚合 | 职责 |
|------|------|
| **玩家 Actor**（演进自 `PlayerSessionBehavior`） | 玩家个人权威数据（经济、背包、个人设置等），**单邮箱串行**；在线会话与离线邮箱策略在 `design.md` 分阶段说明。 |
| **联盟 Actor**（新增） | 联盟级数据（权限、科技、外交占位等），**每联盟一个** `ActorRef`。 |
| **沙盘 Actor**（新增，替代 World/Region/City 拆分） | **每服 × 每玩法模式** 一个实例：该玩法下 **大地图相关对象**（资源点、格子、AOI、行军等）的 **唯一写入边界**；与玩家/联盟通过 **消息契约** 协作。 |
| **Worker** | **无状态**：不持有业务数据；入参传入快照/命令；**计算结果回投** 至沙盘或玩家等 **权威 Actor 邮箱** 再落状态。 |

### 对现有代码的迁移方向（非一次性删改）

- **弃用/收缩**：`WorldRegistryBehavior`、`RegionBehavior`、`CityBehavior` 的 **多级路由**；其职责由 **`WorldMapSandboxBehavior`（名称可调整）** 与 **显式消息** 承接。
- **保留并演进**：`PlayerSessionRegistryBehavior` / `PlayerSessionBehavior` → 与「每玩家一聚合」对齐（命名、生命周期、持久化边界见 `design.md`）。
- **掠夺/结算**：现有 **Map→Player Ask + `battleId` 幂等** 语义 **保留**；调用方从 **City** 改为 **沙盘 Actor** 内逻辑（或委托方法）。

### 交付物

- **`proposal.md`**（本文）、**`design.md`**（聚合边界、消息流、Worker 约束、迁移阶段）、**`tasks.md`**（分阶段任务）、**`specs/game-slg-aggregate-actors-worldmap-worker/spec.md`**（需求与场景）。

## Capabilities

### New Capabilities

- `game-slg-aggregate-actors-worldmap-worker`：玩家 / 联盟 / 沙盘三聚合 + Worker 模式 + 与现有持久化/掠夺协议的衔接。

### Modified / Superseded（以 spec 为准）

- 行为上 **替代** 原 **`game-world-region-city-actors`** 中「必须 Region/City 三级 Actor」的落地方式；**归档时** 对主规格做 **delta 合并或声明替代关系**（见 `tasks.md`）。

## Impact

- **代码**：`game-service` 下 `pekko.world`、`pekko.session`、持久化与 gRPC 路由；**改动面大**，必须 **分阶段**（见 `tasks.md`）。
- **依赖**：可无新 Maven 依赖；Worker 可使用现有 **Spring `@Async` / `Executor`** 或 Pekko **dispatcher**（由 `design.md` 选定）。

## Dependencies

- 现有：`game-actor-persistence-and-cache-write-order`、`game-plunder-settlement-ask-protocol`、`game-player-session-actor`。
- 路线图：[`docs/pekko-game-actor-openspec-roadmap.md`](../../docs/pekko-game-actor-openspec-roadmap.md)。

## Non-Goals

- 本变更 **提案阶段** 不直接合并到 `openspec/specs/` 主规格（待评审后 `openspec archive` 同步）。
- **不在单 PR 内** 完成全量迁移与全玩法沙盘数据模型。
- **不**在本提案中规定具体 **玩法 ID 列表**、**地图尺寸**、**战斗公式**（由业务另案）。

## Risks

- **单沙盘 Actor 邮箱吞吐**：分服 5k 下仍可能在热点期排队；`design.md` 需写 **监控与后续拆分战场/分线** 的触发条件。
- **迁移期双轨**：短期可能并存旧 Region/City 与新沙盘，需 **特性开关** 或 **分支环境**。

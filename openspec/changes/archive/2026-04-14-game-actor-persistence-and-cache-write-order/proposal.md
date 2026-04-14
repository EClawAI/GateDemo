## Why

路线图阶段 6 要求 **Player 钱包与 battleId 幂等**、**City 地图缓存**在进程重启后可恢复，并明确 **写序** 与 **持久化边界**（见 [`docs/pekko-game-actor-openspec-roadmap.md`](../../../docs/pekko-game-actor-openspec-roadmap.md)）。

## What Changes

- **`PlayerPlunderLedger`**：`PlayerSessionBehavior.SettlePlunder` 委托账本；生产实现 **`MongoPlayerPlunderLedger`** 将 **`battleId` 记录**与**金币扣减**写入 **`player_data`**（嵌套 **`PlunderSettlementRecord` 列表**），并 **`saveNow`**。
- **`CityWorldStatePersistence`** / **`MongoCityWorldStatePersistence`**：集合 **`city_world_state`** 保存 **`CityMapCacheState`** 快照；**`CityBehavior`** 启动时 **load**、在写入战报键后 **save**（与 **Envelope** 触发的占位一并持久化）。
- **`WorldRegistry` / `Region` / `City`**：注入 **`CityWorldStatePersistence`**（测试可传 **`null`** 跳过落库）。
- **`PlayerSessionRegistryBehavior`**：构造时注入 **`PlayerPlunderLedger`**；测试使用 **`InMemoryPlayerPlunderLedger`**。

## Capabilities

### New Capabilities

- `game-actor-persistence-and-cache-write-order`：Player/City 持久化边界、写序、崩溃恢复说明。

### Modified Capabilities

- 无（阶段 5 规格仍以协议为主；本变更在独立能力中描述 Mongo 真源）。

## Impact

- **代码**：`game-service` 模型、`persistence`、`pekko.session`、`pekko.world`。
- **依赖**：无新 Maven 依赖（沿用 `spring-boot-starter-data-mongodb`）。

## Dependencies

- 归档：`2026-04-14-add-plunder-settlement-ask-protocol`（Ask 与幂等语义）。

## Non-Goals

- **跨服务 Outbox / 强一致事件表**：仅在设计中列为可选；本实现以 **单进程 Mongo 立即写入** 为主。
- **PlayerSession 与 PlayerData 缓存一致性**的完整驱逐策略：沿用现有 **`AbstractDataManager`** 行为。

## Risks

- **`saveNow` 失败**时内存已变但库未落：与既有 **`AbstractDataManager`** 日志策略一致；生产需监控 Mongo 错误。

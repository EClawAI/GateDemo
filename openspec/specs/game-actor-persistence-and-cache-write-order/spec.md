# game-actor-persistence-and-cache-write-order Specification

## Purpose

规定 **game-service** 中 **Player 掠夺结算**与 **沙盘城地图缓存**的 **Mongo 持久化**、**写序**与**重启恢复**语义；与 [`docs/pekko-game-actor-openspec-roadmap.md`](../../docs/pekko-game-actor-openspec-roadmap.md) 阶段 6 一致，并建立在 [`game-plunder-settlement-ask-protocol`](../game-plunder-settlement-ask-protocol/spec.md) 的 Ask 协议之上。

## Requirements

### Requirement: Player 掠夺结算写入 Mongo 且按 battleId 幂等

`MongoPlayerPlunderLedger` SHALL 在 **`trySettle(playerId, battleId, requestedPlunder)`** 中：

- **从** **`PlayerDataManager.load(playerId)`** 取得 **`PlayerData`**，在 **实体锁**（`synchronized(playerData)`）内检查 **`plunderSettlements`** 是否已含 **`battleId`**。
- **若已含**：SHALL **回复** **`PlunderDuplicate`**，**`actualAmount`** 与首次一致，**且** **不得**修改 **`gold`**。
- **若未含** 且 **`requestedPlunder > 0`**：SHALL 计算 **`actual = min(requested, gold)`**，扣减 **`gold`**，追加 **`PlunderSettlementRecord`**，并调用 **`saveNow(playerId)`**。
- **若** **`requestedPlunder <= 0`**：SHALL **回复** **`PlunderRejected`**（可在账本或会话边界实现其一，行为一致即可）。

#### Scenario: 重启后重复 battleId 不双扣

- **WHEN** Mongo 中已存在某 `battleId` 的结算记录
- **THEN** 再次 **`trySettle`** **不得**再次扣减 **`gold`**，且 **回复** **Duplicate** 与已存 **actual**

### Requirement: 城地图快照可持久化与回填

当 **`CityWorldStatePersistence` 非 null** 时：

- **首次访问某城**：SHALL **`loadSnapshot(persistenceRegionId, cityId)`** 并将条目写入该城对应的 **`CityMapCacheState`**（沙盘内按 `cityId` 懒加载）。
- **在** **`CityEnvelope` 接受并 `putTile`** 之后：SHALL **`saveSnapshot`** 当前快照。
- **在** **`PlunderOk` 或 `PlunderDuplicate` 更新 `battle-*` 键后**：SHALL **`saveSnapshot`**。

#### Scenario: 无持久化 Bean 时行为与旧版一致

- **WHEN** **`CityWorldStatePersistence`** 为 **null**（例如部分测试）
- **THEN** **不得**抛错；**仅** 内存缓存行为

### Requirement: 沙盘注入城态持久化

`WorldMapSandboxConfiguration` SHALL 将 **`CityWorldStatePersistence`** 传入 **`WorldMapSandboxBehavior.create(..., cityWorldStatePersistence, ...)`**。

#### Scenario: Spring 环境使用 Mongo 实现

- **WHEN** 应用启动且存在 **`MongoCityWorldStatePersistence`** Bean
- **THEN** **沙盘** **可**按城加载/保存 **`city_world_state`**

### Requirement: 设计文档描述写序与可选 Outbox

`design.md` SHALL 说明 **Player 先于沙盘落库/承诺** 的顺序，并 **提及** **Outbox/事件表** 为可选强一致方案（本实现可不落地）。

#### Scenario: 审阅者可理解崩溃窗口

- **WHEN** 阅读 `design.md` 写序与崩溃恢复小节
- **THEN** 可见 **钱已扣、地图未写** 的窗口与缓解思路（重试 Duplicate / Outbox）

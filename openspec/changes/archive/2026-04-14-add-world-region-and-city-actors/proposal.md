## Why

阶段 3 已具备 **PlayerSession** 串行钱包路径；路线图要求 **城/分区** 作为 **世界写入边界**，并在阶段 5 掠夺 Ask 前 **冻结路由键与地图缓存归属**。本变更落地 **Region / City Typed Actor** 与 **`regionId` / `cityId` 路由**，为后续 **Map→Player** 协议提供 **唯一权威 City 邮箱**。

## What Changes

- 新增 **`WorldRegistryBehavior`**：按 **`regionId`** 懒创建 **Region** 子 Actor；对外提供 **`RouteToCity(regionId, cityId, …)`** 入口。
- 新增 **`RegionBehavior`**：在分区内按 **`cityId`** 懒创建 **City** 子 Actor（**spawnAnonymous**，避免子名复用问题）。
- 新增 **`CityBehavior`**：持有 **本城地图缓存占位**（进程内结构，**仅 City 邮箱线程**写入）；处理 **带 `targetCityId` 声明** 的入城命令，**不匹配则丢弃**（可观测日志）。
- **`design.md`**：routing key、父子层级、与 `docs/persistence-plan.md` 的 **DB 写序原则**（本阶段不落地具体表）。
- **规格**：新能力 **`game-world-region-city-actors`**。

## Capabilities

### New Capabilities

- `game-world-region-city-actors`：Region/City 层级、路由键、地图缓存单写者、城命令 `targetCityId` 校验。

### Modified Capabilities

- 无（PlayerSession 与世界 Actor 并行存在；不在本变更修改 `game-player-session-actor` 要求）。

## Impact

- **代码**：`game-service` 下 `pekko.world` 包；新增 Spring Bean 暴露 **`ActorRef<WorldRegistryBehavior.Command>`**（供后续 PlayerSession / handler 接入）。
- **依赖**：无新 Maven 依赖。

## Dependencies

- 归档：`2026-04-14-add-player-session-actor`（PlayerSession 已存在）。
- 路线图：`docs/pekko-game-actor-openspec-roadmap.md` 阶段 4。

## Non-Goals

- 不实现 **Cluster Sharding**、不实现 **掠夺 Ask / battleId**（阶段 5）。
- 不修改 **Gate–Game proto**；不将 City **对外**暴露为 gRPC。
- 不在本变更 **持久化** 城图（仅 **原则** + **内存占位**）。

## Risks

- **懒创建** Region/City 数量增长：依赖后续 passivation/阶段 8；首版仅文档说明。

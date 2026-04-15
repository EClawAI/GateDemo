# game-world-region-city-actors Specification

## Purpose

规定 **game-service** 大地图侧 **写入边界** 与 **按城路由** 的语义。实现已收敛为 **单沙盘 Typed Actor**（`WorldMapSandboxBehavior`）：**不再**使用 WorldRegistry → Region → City 多级 Actor；逻辑上的 **`regionId`（持久化槽位）+ `cityId`** 仍用于 **城快照键** 与消息中的 **`targetCityId`** 校验。

与 [`game-slg-aggregate-actors-worldmap-worker`](../game-slg-aggregate-actors-worldmap-worker/spec.md) 对齐。

## Requirements

### Requirement: 沙盘为大地图唯一路由入口

`game-service` SHALL 通过 **`WorldMapSandboxBehavior`**（Spring Bean `worldMapSandbox`）接收 **城/地图侧** 命令（如 `CityEnvelope`、`CityPingSeq`、`SettlePlunderVictim`）；**不得**在 **gRPC/Netty 回调线程**上执行地图业务逻辑（调用方 **tell** 前仅做轻量封装）。

#### Scenario: 单邮箱串行

- **WHEN** 向沙盘连续投递多条命令
- **THEN** SHALL 在 **同一** Typed Actor 邮箱内 **顺序**处理（全局单写入者；城内逻辑由 `targetCityId` 与 per-city 缓存区分）

### Requirement: targetCityId 与业务一致

凡命令携带 **`targetCityId`**，`WorldMapSandboxBehavior` SHALL **仅当** 该字段与命令语义一致时更新 **对应城** 的缓存占位；否则 SHALL **拒绝**（不修改该城缓存、可记录日志）。

#### Scenario: 错误 target 不污染

- **WHEN** 投递 **声明错误 targetCityId** 的入城类命令
- **THEN** **不得**对无关城缓存产生副作用

### Requirement: 每城地图缓存单写者（沙盘邮箱内）

**与某 `cityId` 关联的 `CityMapCacheState`** SHALL **仅**在 **`WorldMapSandboxBehavior`** 的消息处理路径上 **突变**；其他组件 SHALL **不**共享 **可变** 缓存引用 **绕过** 沙盘邮箱。

#### Scenario: 缓存归属文档化

- **WHEN** 审阅 `design.md` 与本规格
- **THEN** 可见 **单写者** 与 **禁止旁路写** 的明确陈述

### Requirement: DB 写序原则（与 persistence 文档对齐）

`design.md` SHALL 描述 **沙盘侧** 内存态与持久化的 **顺序原则**，并 **可引用** [`docs/persistence-plan.md`](../../docs/persistence-plan.md)。

#### Scenario: 可追溯至 persistence-plan

- **WHEN** 阅读 `design.md` 的持久化小节
- **THEN** 存在对 `persistence-plan.md` 或等价仓库内文档的引用

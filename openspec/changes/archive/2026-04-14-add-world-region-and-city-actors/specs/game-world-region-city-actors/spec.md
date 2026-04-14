# game-world-region-city-actors Specification (delta)

## ADDED Requirements

### Requirement: WorldRegistry 按 regionId 提供 City 路由入口

`game-service` SHALL 提供 **Typed `WorldRegistryBehavior` Actor**，支持 **`RouteToCity(regionId, cityId, cityCommand)`**（或等价结构）：将 **`cityCommand`** 投递至 **由 `(regionId, cityId)` 唯一确定** 的 **`CityBehavior`** 邮箱；**不得**在 **gRPC/Netty 回调线程**上执行城业务逻辑（调用方 **tell** 前仅做轻量封装）。

#### Scenario: 懒创建 Region 与 City

- **WHEN** 首次对某 `(regionId, cityId)` 路由命令
- **THEN** 实现 **创建**（或复用已存在）**Region** 与 **City** Typed Actor，且后续同键路由 **复用** 同一 City 邮箱

### Requirement: 同一 cityId 消息串行处理

对 **固定** `(regionId, cityId)`，所有进入 **该** `CityBehavior` 邮箱的消息 SHALL **顺序**处理（单 Actor 语义）。

#### Scenario: 顺序可观测

- **WHEN** 测试向 **同一** City 连续投递 **多条**可观测命令（如带序号）
- **THEN** 处理顺序与投递顺序一致（在 **同一线程/邮箱**语义下）

### Requirement: targetCityId 与 City 绑定一致

凡 **City** 处理的命令若携带 **`targetCityId`（或等价字段）**，`CityBehavior` SHALL **仅当** `targetCityId` **等于** 本 Actor 绑定的 **`cityId`** 时执行业务逻辑；否则 SHALL **拒绝**（不修改地图缓存占位、可记录日志）。

#### Scenario: 错误 target 不污染正确城

- **WHEN** 向 **city A** 的邮箱投递 **声明 target 为 city B** 的命令
- **THEN** **city A** 不执行该命令的业务副作用（与 **city B** 的正确队列无关）

### Requirement: 地图缓存单写者（City）

**与某 `cityId` 关联的地图缓存状态**（占位结构即可）SHALL **仅**在 **对应 `CityBehavior`** 的消息处理路径上 **突变**；其他组件 SHALL **不**共享 **可变** 缓存引用 **绕过** City 邮箱。

#### Scenario: 缓存归属文档化

- **WHEN** 审阅 `design.md` 与本规格
- **THEN** 可见 **单写者** 与 **禁止旁路写** 的明确陈述

### Requirement: DB 写序原则（与 persistence 文档对齐）

`design.md` SHALL 描述 **同一 City** 下 **内存态与持久化** 的 **顺序原则**，并 **可引用** [`docs/persistence-plan.md`](../../../docs/persistence-plan.md)；本阶段 **不**要求实现具体存储或 Outbox。

#### Scenario: 可追溯至 persistence-plan

- **WHEN** 阅读 `design.md` 的持久化小节
- **THEN** 存在对 `persistence-plan.md` 或等价仓库内文档的引用

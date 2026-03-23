## Context

GateDemo 是一个无状态游戏网关演示项目，采用 Spring Boot 3.2 + Java 17 构建。当前架构包含 gate-service（Netty 网关）、game-service（gRPC 游戏服务）、login-service（HTTP 登录）、center-service（配置中心）以及 common（共享 Proto/DTO）。

目前所有服务没有数据持久化能力。game-service 接收 gRPC 消息，处理完毕后数据即丢弃。Redis 仅用于服务发现和状态同步，不做业务数据存储。

项目缺少一个承载通用核心业务逻辑的底层框架模块。现有 `common` 模块仅存放 Proto 生成类和共享 DTO，定位是数据定义层，不适合放业务框架代码。项目已有一个空的 `core/` 目录结构（含 `persistence/mongo` 包骨架），但无 pom.xml，未注册到 parent modules。

## Goals / Non-Goals

**Goals:**
- 正式建立 `core` 为底层核心业务框架模块，包结构按职责域划分，供所有上层 service 依赖
- 明确 `common`（数据定义层：Proto/DTO）与 `core`（核心业务框架层：持久化/缓存/事件等）的分工
- 首批能力：在 core 中提供通用的 MongoDB 持久化抽象，支持内存缓存 + dirty tracking + 定时批量刷盘 + 立即写入
- 在 game-service 中实现玩家数据的 MongoDB 落地，首次登录生成模拟数据
- 各服务自行配置 MongoDB 连接，core 不提供 auto-configuration

**Non-Goals:**
- 本次不在 core 中引入持久化以外的其他能力（事件、缓存框架等），但包结构预留扩展空间
- 不实现完整的 Repository/DAO 模式，保持轻量级抽象
- 不做 MongoDB 分片/副本集配置，Demo 环境使用单节点
- 不改变现有 Redis 的用途（服务发现、状态同步等继续使用 Redis）
- 不对 gate-service / login-service / center-service 做任何改动
- 不修改 gRPC Proto 定义，新消息类型通过现有 msgType 字段区分

## Decisions

### D1: core 模块定位为底层核心业务框架

**决策**: core 定位为各上层 service 通用核心业务逻辑的承载层，包结构按职责域划分（`persistence/`、`model/` 等），与 `common`（数据定义层）互补。本次首批实现 `persistence` 包，但模块结构预留其他职责域的扩展空间。

**替代方案**:
- 方案A: core 仅做纯持久化层 — 定位过窄，后续加其他通用业务逻辑时需要再建模块或改定位
- 方案B: 把核心业务逻辑直接放 common — common 当前是纯数据定义（Proto/DTO），混入业务框架会导致职责模糊，且 common 的依赖链很轻，不应引入 MongoDB 等重依赖

**理由**: `common` = 数据定义层（轻依赖，所有模块可引用），`core` = 核心业务框架层（可引入 MongoDB、Spring 等较重依赖，上层 service 按需引用）。这种分层使依赖关系清晰：`common ← core ← game-service / gate-service / ...`。

**core 包结构规划**:
```
com.clawai.gatedemo.core
├── persistence/       ← 本次实现：MongoDB 持久化抽象
│   ├── BaseEntity.java
│   └── AbstractDataManager.java
├── model/             ← 预留：通用业务模型基类
├── event/             ← 预留：跨服务事件抽象
├── cache/             ← 预留：通用缓存抽象
└── config/            ← 预留：通用配置抽象
```

### D2: 轻度泛型 AbstractDataManager 而非完整 Repository

**决策**: 提供 `AbstractDataManager<ID, T extends BaseEntity<ID>>` 泛型基类，封装缓存、dirty tracking、定时刷盘逻辑。子类只需实现 `createDefault(ID id)` 和指定集合名称。

**替代方案**:
- 方案A: 完整 Spring Data MongoDB Repository 模式 — 过重，Demo 项目不需要
- 方案B: 每个服务各写各的 — 重复代码，后续维护成本高

**理由**: 轻度泛型在复用性和简洁性之间取得平衡。核心逻辑（缓存 + dirty + flush）是通用的，数据模型和初始化逻辑是特化的。

### D3: MongoTemplate 由子类/业务层注入

**决策**: AbstractDataManager 通过构造函数接收 MongoTemplate，由使用方（game-service 等）通过 Spring 配置提供。core 模块不做 auto-configuration。

**替代方案**: core 提供 `@AutoConfiguration`，扫描 `spring.data.mongodb` 配置自动创建 MongoTemplate。

**理由**: 不同微服务可能连接不同的 MongoDB 实例或数据库，auto-config 反而限制了灵活性。各服务自己在 application.yml 配置 `spring.data.mongodb.uri` 即可，Spring Boot 自动创建 MongoTemplate。

### D4: ConcurrentHashMap 作为内存缓存

**决策**: 使用 `ConcurrentHashMap<ID, T>` 作为内存缓存，`ConcurrentHashMap.newKeySet()` 作为 dirty 标记集合。

**替代方案**: Caffeine / Guava Cache 带 TTL 和淘汰策略。

**理由**: Demo 项目玩家数量有限，无需复杂的缓存淘汰策略。ConcurrentHashMap 无额外依赖，线程安全，性能足够。提供 `evict(id)` 方法供玩家下线时手动清理。

### D5: 首次登录判定由 Game 自行查 MongoDB

**决策**: `PlayerDataManager.load(playerId)` 查询 MongoDB，若无记录则调用 `createDefault()` 生成模拟数据并立即写入。

**替代方案**: Gate 侧判定新老玩家，通过消息参数告知 Game。

**理由**: Game 自行判定更自洽，不依赖外部状态传递，也不需要改动 Gate 逻辑。

### D6: 定时刷盘间隔 30 秒

**决策**: 默认 30 秒执行一次 `flushAll()`，通过配置 `game.persistence.flush-interval` 可调。

**理由**: Demo 项目 30 秒足够频繁，方便演示观察。生产环境可调整为 5 分钟。

## Risks / Trade-offs

**[内存占用]** 所有已加载的玩家数据常驻内存 → 提供 `evict()` 方法供玩家下线时清理；Demo 场景玩家量小，可接受。

**[数据丢失窗口]** dirty 数据在两次刷盘之间如果进程崩溃会丢失 → 关键操作（首次登录、显式保存）走 `saveNow()`；定时刷盘是兜底策略，Demo 可接受此 trade-off。

**[并发修改]** 多线程同时 update 同一玩家数据 → `update()` 方法内对单个玩家数据加锁（synchronized on entity），保证修改原子性。

**[MongoDB 不可用]** MongoDB 挂掉时 load 会失败 → 日志告警，不影响已在内存中的数据；定时刷盘失败跳过，下次重试。

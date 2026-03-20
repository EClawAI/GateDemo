## Context

GateDemo 是一个无状态游戏网关演示项目，采用 Spring Boot 3.2 + Java 17 构建。当前架构包含 gate-service（Netty 网关）、game-service（gRPC 游戏服务）、login-service（HTTP 登录）、center-service（配置中心）以及 common（共享 Proto/DTO）。

目前所有服务没有数据持久化能力。game-service 接收 gRPC 消息，处理完毕后数据即丢弃。Redis 仅用于服务发现和状态同步，不做业务数据存储。

项目已有一个空的 `core/` 目录结构（含 `persistence/mongo` 包骨架），但无 pom.xml，未注册到 parent modules。

## Goals / Non-Goals

**Goals:**
- 在 core 模块中提供通用的 MongoDB 持久化抽象，支持内存缓存 + dirty tracking + 定时批量刷盘 + 立即写入
- 在 game-service 中实现玩家数据的 MongoDB 落地，首次登录生成模拟数据
- 设计足够通用，使未来其他微服务可直接复用 core 的持久化层
- 各服务自行配置 MongoDB 连接，core 不提供 auto-configuration

**Non-Goals:**
- 不实现完整的 Repository/DAO 模式，保持轻量级抽象
- 不做 MongoDB 分片/副本集配置，Demo 环境使用单节点
- 不改变现有 Redis 的用途（服务发现、状态同步等继续使用 Redis）
- 不对 gate-service / login-service / center-service 做任何改动
- 不修改 gRPC Proto 定义，新消息类型通过现有 msgType 字段区分

## Decisions

### D1: core 模块定位为纯持久化层

**决策**: core 仅包含持久化抽象（当前是 MongoDB），不引入缓存、事件、通用配置等其他底层能力。

**替代方案**: 将 core 定位为全能微服务底座（包含 cache、event、config 等）。

**理由**: 项目已有 common 模块放共享 DTO/Proto。core 聚焦持久化可避免职责模糊，后续需要时再扩展。保持 YAGNI 原则。

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

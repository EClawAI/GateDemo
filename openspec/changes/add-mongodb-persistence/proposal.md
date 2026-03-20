## Why

当前 GateDemo 项目所有微服务均无数据持久化能力，game-service 处理完消息后数据即丢弃，无法支撑真实游戏场景中的玩家数据存储需求。需要引入 MongoDB 作为数据落地层，在 core 模块中构建通用的持久化抽象，使当前及未来的微服务都能复用。

## What Changes

- 新增 `core` Maven 模块，包含基于 MongoDB 的通用持久化抽象层（`AbstractDataManager<ID, T>`），提供内存缓存、脏数据追踪、立即写入和定时批量刷盘能力
- 在 `game-service` 中新增 `PlayerData` 数据模型和 `PlayerDataManager`，实现玩家数据的 MongoDB 落地
- 玩家首次登录时自动生成模拟数据并立即写入 MongoDB；后续登录更新 `lastLoginTime` 并立即写入
- 业务数据变更默认标记 dirty，由定时任务（30s 间隔）批量刷盘；也支持显式调用立即写入接口
- 在 `GameMessageHandler` 中新增 `player.login` 和 `player.save` 消息类型
- `docker-compose.yml` 新增 MongoDB 服务

## Capabilities

### New Capabilities
- `data-persistence-core`: core 模块通用持久化抽象层，包含 BaseEntity 接口、AbstractDataManager 泛型基类（缓存、dirty tracking、定时刷盘、立即写入）
- `player-data-mongo`: game-service 中的玩家数据 MongoDB 落地，包含 PlayerData 模型、PlayerDataManager 实现、模拟数据生成、新消息类型集成

### Modified Capabilities
- `capability-game-logic-exceptions`: GameMessageHandler 新增 player.login / player.save 消息类型处理逻辑

## Impact

- **新模块**: `core` 模块加入 parent pom 的 modules 列表，作为 game-service 的依赖
- **依赖变更**: core 引入 `spring-boot-starter-data-mongodb`；game-service 新增 core 依赖
- **配置变更**: game-service 的 application.yml 新增 MongoDB 连接配置和刷盘间隔配置
- **基础设施**: docker-compose.yml 新增 MongoDB 容器和持久化 volume
- **API 影响**: gRPC 消息流新增 `player.login` / `player.save` 两种消息类型

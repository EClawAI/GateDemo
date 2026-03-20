## ADDED Requirements

### Requirement: PlayerData 数据模型

game-service SHALL 定义 `PlayerData` 类，使用 `@Document` 注解映射到 MongoDB 的 `player_data` 集合，实现 `BaseEntity<Long>` 接口。字段包括：
- `_id` (Long): 玩家 ID，即 playerId
- `nickname` (String): 昵称
- `level` (int): 等级
- `exp` (long): 经验值
- `gold` (long): 金币
- `diamond` (int): 钻石
- `vipLevel` (int): VIP 等级
- `createTime` (LocalDateTime): 创建时间
- `lastLoginTime` (LocalDateTime): 最后登录时间
- `loginCount` (int): 登录次数
- `items` (List\<ItemData\>): 物品列表

`ItemData` 为嵌套类，包含 `itemId` (int) 和 `count` (int)。

#### Scenario: PlayerData 可序列化到 MongoDB
- **WHEN** PlayerData 实例被保存到 MongoDB
- **THEN** 所有字段正确写入 `player_data` 集合
- **AND** `_id` 字段使用 playerId 作为主键

### Requirement: PlayerDataManager 继承 AbstractDataManager

game-service SHALL 定义 `PlayerDataManager extends AbstractDataManager<Long, PlayerData>`，作为 Spring Bean 注入。MUST 实现 `createDefault(Long playerId)` 方法，使用 playerId 作为种子生成确定性的模拟数据。

初始模拟数据 SHALL 包含：
- `nickname`: "Player_{playerId}"
- `level`: 1
- `exp`: 0
- `gold`: 1000
- `diamond`: 100
- `vipLevel`: 0
- `createTime` / `lastLoginTime`: 当前时间
- `loginCount`: 1
- `items`: 3 个初始物品（itemId: 1001 剑 x1, 2001 药水 x5, 3001 盾牌 x1）

#### Scenario: 新玩家模拟数据生成
- **WHEN** 首次加载一个不存在的 playerId
- **THEN** 自动生成上述模拟数据
- **AND** 同一 playerId 多次初始化生成相同数据（确定性）

### Requirement: 定时刷盘调度

game-service SHALL 通过 `@Scheduled` 注解或独立的调度 Bean 以配置间隔（默认 30 秒，`game.persistence.flush-interval`）调用 `PlayerDataManager.flushAll()`。

#### Scenario: 30 秒定时刷盘
- **WHEN** 服务运行 30 秒后
- **THEN** 自动触发一次 `flushAll()`
- **AND** 所有 dirty 的 PlayerData 被写入 MongoDB

### Requirement: MongoDB 连接配置

game-service SHALL 在 `application.yml` 中配置 MongoDB 连接，通过 `spring.data.mongodb.uri` 属性。默认值 SHALL 为 `mongodb://localhost:27017/gatedemo`，支持通过环境变量 `MONGO_HOST`、`MONGO_PORT`、`MONGO_DB` 覆盖。

#### Scenario: 默认本地连接
- **WHEN** 未设置 MongoDB 环境变量
- **THEN** 连接到 `localhost:27017` 的 `gatedemo` 数据库

#### Scenario: Docker 环境变量覆盖
- **WHEN** 设置 `MONGO_HOST=mongodb`
- **THEN** 连接到 `mongodb:27017` 的 `gatedemo` 数据库

### Requirement: Docker Compose MongoDB 服务

docker-compose.yml SHALL 新增 MongoDB 服务，使用 `mongo:7-jammy` 镜像，暴露 27017 端口，挂载持久化 volume `mongo-data`。game-service 容器 SHALL 配置 MongoDB 环境变量指向该服务。

#### Scenario: Docker Compose 启动包含 MongoDB
- **WHEN** 执行 `docker-compose up`
- **THEN** MongoDB 容器正常启动并监听 27017 端口
- **AND** game-service 可连接到 MongoDB

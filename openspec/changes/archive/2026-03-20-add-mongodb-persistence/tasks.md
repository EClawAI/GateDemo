## 1. Core 框架模块搭建

- [x] 1.1 创建 `core/pom.xml`，parent 指向 `gate-demo-parent`，引入 `common` 模块、`spring-boot-starter-data-mongodb` 和 lombok 依赖
- [x] 1.2 在 parent `pom.xml` 的 `<modules>` 中注册 `core` 模块，在 `<dependencyManagement>` 中添加 core artifact
- [x] 1.3 建立 core 包结构 `com.clawai.gatedemo.core`，创建 `persistence` 子包（本次实现），其余职责域子包按需预留

## 2. 通用持久化抽象（core.persistence）

- [x] 2.1 实现 `BaseEntity<ID>` 接口，定义 `getId()` 方法
- [x] 2.2 实现 `AbstractDataManager<ID, T extends BaseEntity<ID>>`，包含 ConcurrentHashMap 缓存和 dirty set，定义抽象方法 `createDefault()`、`getCollectionName()`、`getEntityClass()`
- [x] 2.3 实现 `load(ID id)` 方法：缓存优先 → MongoDB 查询 → createDefault + 立即写入
- [x] 2.4 实现 `get(ID id)` 方法：仅从缓存读取
- [x] 2.5 实现 `update(ID id, Consumer<T> mutator)` 方法：修改内存 + synchronized 保证原子性 + 标记 dirty
- [x] 2.6 实现 `saveNow(ID id)` 方法：立即写入 MongoDB + 清除 dirty 标记
- [x] 2.7 实现 `flushAll()` 方法：遍历 dirty set 批量写入，失败 id 保留重试
- [x] 2.8 实现 `evict(ID id)` 方法：dirty 则先 save 再移除缓存

## 3. Game-Service 数据模型

- [x] 3.1 创建 `ItemData` 类（`game-service/model/ItemData.java`），包含 itemId 和 count 字段
- [x] 3.2 创建 `PlayerData` 实体类（`game-service/model/PlayerData.java`），`@Document("player_data")` 注解，实现 `BaseEntity<Long>`，包含所有字段

## 4. PlayerDataManager 实现

- [x] 4.1 创建 `PlayerDataManager extends AbstractDataManager<Long, PlayerData>`，注入 MongoTemplate，实现 `createDefault()` 生成模拟数据
- [x] 4.2 创建定时刷盘调度（`@Scheduled` 或独立 Bean），以 `game.persistence.flush-interval`（默认 30000ms）间隔调用 `flushAll()`

## 5. GameMessageHandler 集成

- [x] 5.1 在 `GameMessageHandler` 中注入 `PlayerDataManager` 依赖
- [x] 5.2 新增 `player.login` 消息处理：调用 load → 更新 lastLoginTime / loginCount → saveNow → 通过 sink 回发玩家数据
- [x] 5.3 新增 `player.save` 消息处理：调用 saveNow → 通过 sink 回发确认消息

## 6. 配置与基础设施

- [x] 6.1 `game-service/pom.xml` 添加 core 模块依赖（替代直接依赖 common，通过 core 传递获得 common）
- [x] 6.2 `game-service/application.yml` 添加 `spring.data.mongodb.uri` 配置（支持环境变量覆盖）和 `game.persistence.flush-interval` 配置
- [x] 6.3 `docker-compose.yml` 新增 MongoDB 服务（`mongo:7-jammy`，端口 27017，volume `mongo-data`），game-service 容器添加 MongoDB 环境变量

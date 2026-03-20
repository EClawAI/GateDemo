## ADDED Requirements

### Requirement: BaseEntity 通用实体接口

core 模块 SHALL 提供 `BaseEntity<ID>` 接口，定义 `ID getId()` 方法，作为所有持久化实体的基础契约。所有需要被 `AbstractDataManager` 管理的数据模型 MUST 实现此接口。

#### Scenario: 实体类实现 BaseEntity
- **WHEN** 业务模块定义新的数据模型类
- **THEN** 该类实现 `BaseEntity<ID>` 接口并提供 `getId()` 实现
- **AND** 可被 `AbstractDataManager` 管理

### Requirement: AbstractDataManager 泛型持久化基类

core 模块 SHALL 提供 `AbstractDataManager<ID, T extends BaseEntity<ID>>` 抽象类，封装以下通用能力：
- 内存缓存（ConcurrentHashMap）
- 脏数据追踪（dirty set）
- 从 MongoDB 加载数据到缓存
- 立即写入 MongoDB
- 定时批量刷盘

子类 MUST 实现 `createDefault(ID id)` 方法来定义新实体的初始化逻辑。子类 MUST 实现 `getCollectionName()` 方法来指定 MongoDB 集合名称。子类 MUST 实现 `getEntityClass()` 方法来指定实体类型。

#### Scenario: 构造函数注入 MongoTemplate
- **WHEN** 子类实例化时
- **THEN** 通过构造函数接收 `MongoTemplate` 实例
- **AND** 不依赖 auto-configuration

### Requirement: 数据加载与首次创建

`AbstractDataManager.load(ID id)` SHALL 先检查内存缓存，若命中则直接返回。若缓存未命中，SHALL 查询 MongoDB。若 MongoDB 中无记录，SHALL 调用 `createDefault(id)` 生成默认数据并立即写入 MongoDB，然后放入缓存并返回。若 MongoDB 中有记录，SHALL 放入缓存并返回。

#### Scenario: 缓存命中直接返回
- **WHEN** 调用 `load(id)` 且该 id 已在内存缓存中
- **THEN** 直接返回缓存中的实体，不查询 MongoDB

#### Scenario: 缓存未命中但 MongoDB 有记录
- **WHEN** 调用 `load(id)` 且缓存未命中但 MongoDB 中存在该记录
- **THEN** 从 MongoDB 读取并放入缓存后返回

#### Scenario: 全新实体自动创建
- **WHEN** 调用 `load(id)` 且缓存和 MongoDB 中均无记录
- **THEN** 调用 `createDefault(id)` 生成实体
- **AND** 立即写入 MongoDB
- **AND** 放入缓存后返回

### Requirement: 仅缓存读取

`AbstractDataManager.get(ID id)` SHALL 仅从内存缓存中获取数据，不触发 MongoDB 查询。若缓存中不存在，SHALL 返回 null。

#### Scenario: 缓存中存在数据
- **WHEN** 调用 `get(id)` 且缓存中有该实体
- **THEN** 返回缓存中的实体

#### Scenario: 缓存中不存在数据
- **WHEN** 调用 `get(id)` 且缓存中无该实体
- **THEN** 返回 null，不触发 MongoDB 查询

### Requirement: 数据更新与脏标记

`AbstractDataManager.update(ID id, Consumer<T> mutator)` SHALL 从缓存中取出实体，执行 mutator 修改，然后将该 id 加入 dirty set。对同一实体的并发 update 调用 MUST 通过 synchronized 保证修改原子性。若缓存中不存在该 id 的实体，SHALL 抛出异常或忽略（日志告警）。

#### Scenario: 正常更新并标记 dirty
- **WHEN** 调用 `update(id, mutator)` 且缓存中有该实体
- **THEN** mutator 被执行，实体数据被修改
- **AND** 该 id 被加入 dirty set

#### Scenario: 并发更新同一实体
- **WHEN** 两个线程同时调用 `update(id, ...)` 修改同一实体
- **THEN** 两次修改串行执行，不会出现数据竞争

### Requirement: 立即写入

`AbstractDataManager.saveNow(ID id)` SHALL 将缓存中该 id 的实体立即写入 MongoDB（upsert），并从 dirty set 中移除该 id。若缓存中不存在该实体，SHALL 忽略。

#### Scenario: 立即持久化并清除脏标记
- **WHEN** 调用 `saveNow(id)` 且缓存中有该实体
- **THEN** 实体被写入 MongoDB
- **AND** 该 id 从 dirty set 中移除

### Requirement: 定时批量刷盘

`AbstractDataManager.flushAll()` SHALL 遍历 dirty set 中的所有 id，将对应的缓存实体批量写入 MongoDB，然后清空 dirty set。刷盘过程中若某个实体写入失败，SHALL 记录日志但继续处理其余实体，失败的 id 保留在 dirty set 中等待下次重试。

#### Scenario: 定时触发刷盘
- **WHEN** 定时任务触发 `flushAll()` 且 dirty set 非空
- **THEN** 所有 dirty 实体被写入 MongoDB
- **AND** dirty set 被清空

#### Scenario: 部分写入失败
- **WHEN** `flushAll()` 执行时某个实体写入 MongoDB 失败
- **THEN** 其余实体正常写入
- **AND** 失败的 id 保留在 dirty set 中，日志记录错误

#### Scenario: 无脏数据时不操作
- **WHEN** 定时任务触发 `flushAll()` 且 dirty set 为空
- **THEN** 不执行任何 MongoDB 写操作

### Requirement: 缓存驱逐

`AbstractDataManager.evict(ID id)` SHALL 将指定 id 的实体从缓存中移除。若该 id 在 dirty set 中，MUST 先执行 `saveNow(id)` 将数据写入 MongoDB，再从缓存中移除。

#### Scenario: 干净数据直接驱逐
- **WHEN** 调用 `evict(id)` 且该 id 不在 dirty set 中
- **THEN** 实体从缓存中移除

#### Scenario: 脏数据先保存再驱逐
- **WHEN** 调用 `evict(id)` 且该 id 在 dirty set 中
- **THEN** 先将实体写入 MongoDB
- **AND** 从 dirty set 和缓存中移除

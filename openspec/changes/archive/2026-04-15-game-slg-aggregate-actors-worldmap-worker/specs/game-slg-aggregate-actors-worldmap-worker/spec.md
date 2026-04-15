# game-slg-aggregate-actors-worldmap-worker — Delta

## ADDED Requirements

### Requirement: 沙盘聚合为单服单玩法大地图的唯一写入边界

本玩法下资源点、可寻址格子、行军实例、沙盘侧 AOI 派生写回等 SHALL 仅在 `WorldMapSandboxBehavior`（或等价命名）邮箱内修改内存状态；不得在 Worker 线程直接写入沙盘权威结构。

#### Scenario: Worker 仅返回结果

- **WHEN** 发起重计算（战斗、路径批量评估等）
- **THEN** Worker SHALL 仅输出结果 DTO；权威 Actor SHALL 在下一跳邮箱消息中应用变更

### Requirement: 玩家聚合与个人权威数据路径

玩家 Actor（演进自 `PlayerSessionBehavior`）SHALL 串行处理个人指令；钱包与 `battleId` 幂等 SHALL 与现有 `PlayerPlunderLedger` / `player_data` 语义兼容，除非经独立 change 明确变更。

#### Scenario: 掠夺写序不变

- **WHEN** 沙盘发起掠夺结算
- **THEN** SHALL 先完成 Player 侧 `SettlePlunder` 成功语义，再更新沙盘侧表现

### Requirement: 联盟聚合独立

联盟 Actor SHALL 按 `allianceId` 分实例；SHALL 与沙盘、玩家通过显式消息协作，不得隐式共享可变静态状态。

#### Scenario: 联盟变更不污染玩家邮箱

- **WHEN** 仅修改联盟科技
- **THEN** 不得要求玩家 Actor 直接修改联盟结构体（应 tell 联盟或 Ask）

### Requirement: Worker 无状态

Worker 执行单元 SHALL NOT 持有玩家 / 联盟 / 沙盘的权威引用；SHALL 仅通过参数接收输入。

#### Scenario: 并发安全

- **WHEN** 多个 Worker 任务并发
- **THEN** 不得因共享可变单例导致数据竞争

### Requirement: 迁移期特性开关

SHALL 提供配置项（或等价机制）在旧 Region/City 路径与新沙盘路径之间切换，直至迁移完成。

#### Scenario: 默认兼容

- **WHEN** 未显式开启新路径
- **THEN** SHALL 保持与发布前行为兼容（或文档声明的降级行为）

### Requirement: 淘汰多级 World/Region/City

迁移完成后 SHALL 移除 `WorldRegistryBehavior` / `RegionBehavior` / `CityBehavior` 的职责重复实现，或保留薄适配器且在文档中标注废弃时间表。

#### Scenario: 无双写冲突

- **WHEN** 新路径全量开启
- **THEN** 不得同时对同一地图事件存在 City 与沙盘双写（除非兼容期显式双写策略与开关）

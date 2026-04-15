# game-slg-aggregate-actors-worldmap-worker Specification

## Purpose

规定 **game-service** 在 SLG 分服场景下 **玩家 / 联盟 / 沙盘** 三类聚合与 **无状态 Worker** 的职责边界与掠夺写序；大地图侧 **仅** `WorldMapSandboxBehavior`（与 `game-world-region-city-actors` 现行语义一致）。

## Requirements

### Requirement: 沙盘聚合为单服单玩法大地图的唯一写入边界

本玩法下资源点、可寻址格子、行军实例、沙盘侧 AOI 派生写回等 SHALL 仅在 `WorldMapSandboxBehavior` 邮箱内修改内存状态；不得在 Worker 线程直接写入沙盘权威结构。

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

### Requirement: 无双写冲突

SHALL NOT 对同一地图事件保留 **WorldRegistry / Region / City** 与 **沙盘** 两套并行写入路径；实现 **仅** 暴露沙盘根 Actor。

#### Scenario: 单一路径

- **WHEN** 应用启动
- **THEN** 大地图侧 **仅** `WorldMapSandboxBehavior`（经 `WorldMapSandboxConfiguration`）注册为 Spring Bean

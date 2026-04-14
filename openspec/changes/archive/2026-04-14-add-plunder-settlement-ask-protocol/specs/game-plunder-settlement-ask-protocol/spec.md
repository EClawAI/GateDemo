# game-plunder-settlement-ask-protocol Specification (delta)

## ADDED Requirements

### Requirement: Player 对 SettlePlunder 以 battleId 幂等扣款

`PlayerSessionBehavior` SHALL 处理 **`SettlePlunder(battleId, requestedPlunder, replyTo)`**：

- **若** `battleId` **已**存在已提交记录：SHALL **回复** **`PlunderDuplicate`**，**actual** 与 **首次**提交一致，**且** **不得**再次扣减钱包。
- **若** `battleId` **未**存在：SHALL 计算 **`actual = min(requestedPlunder, wallet)`**（钱包为占位实现），扣减钱包，记录 **`battleId → actual`**，并 **回复** **`PlunderOk`**。
- **若** `requestedPlunder <= 0`：SHALL **回复** **`PlunderRejected`**。

#### Scenario: 重复 battleId 不双扣

- **WHEN** 对同一 `battleId` 连续两次 **SettlePlunder**（相同或不同 `requestedPlunder`）
- **THEN** 第二次 **回复** **Duplicate** 且 **actual** 与第一次一致，**且** 钱包总量 **仅**反映第一次扣减

### Requirement: Registry 可解析在线 PlayerSession

`PlayerSessionRegistryBehavior` SHALL 提供 **`GetPlayerSession(playerId, replyTo)`**，`replyTo` **MUST** 收到 **`Optional<ActorRef<PlayerSessionBehavior.Command>>`**（在线非空，否则空）。

#### Scenario: 无会话时为空

- **WHEN** `playerId` 无活跃会话
- **THEN** `replyTo` 收到 **EMPTY**

### Requirement: City 通过 Ask 链完成 Map→Player 结算

`CityBehavior` SHALL 处理 **`SettlePlunderVictim(targetCityId, battleId, victimPlayerId, requestedPlunder, replyTo)`**：

- **MUST** **校验** `targetCityId` **等于** 本城 `cityId`。
- **SHALL** **异步** `Ask` **Registry** → **Player** `SettlePlunder`（**不得** 在 gRPC 回调线程执行）。
- **在** 收到 **`PlunderOk`** 或 **`PlunderDuplicate`** 后：**SHALL** 更新 **本城** 地图缓存占位（例如 `battle-{battleId}`）。
- **在** 失败（离线、超时、拒绝）时：**SHALL** 向 **`replyTo`** 交付 **`PlunderSettlementResult.Failed`**，**且** **不得** 写入上述缓存键。

#### Scenario: 受害者离线则失败

- **WHEN** `GetPlayerSession` 返回 **EMPTY**
- **THEN** `replyTo` 收到 **Failed** 且 **reason** 表示 **offline**（或等价语义）

### Requirement: City 结算状态机可审计

`design.md` SHALL 描述 **City** 侧 **Pending / Committed / Failed** 状态含义（实现 **可** 不显式枚举）。

#### Scenario: 文档与实现对齐

- **WHEN** 审阅 `design.md` 状态机小节
- **THEN** 可见 **Committed** 仅在 **Player 已承诺** 之后

### Requirement: 自动化测试覆盖幂等与 Ask 路径

项目 SHALL 提供 **自动化测试** 验证：(1) **首次**结算与 **重复** `battleId` **返回相同** `actual`；(2) **受害者离线** 时 **失败**。

#### Scenario: 测试通过

- **WHEN** 执行 `game-service` 相关测试
- **THEN** 上述用例通过

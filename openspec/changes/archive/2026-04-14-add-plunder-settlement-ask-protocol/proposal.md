## Why

路线图阶段 5 要求 **地图侧** 在 **不持有钱包真源** 的前提下完成掠夺结算：**Player** 以 **`battleId` 幂等** 扣款并返回 **`actual`**，**City** 再写入战报/地图占位。本变更落地 **Ask 协议** 与 **Player 侧幂等表**，避免「地图已结、钱未扣」或双扣。

## What Changes

- **`PlayerSessionBehavior`**：新增 **`SettlePlunder(battleId, requested, replyTo)`**；内存 **`battleId → actual`** 幂等与 **`walletGold`** 占位（阶段 6 再对接 Mongo）。
- **`PlayerSessionRegistryBehavior`**：新增 **`GetPlayerSession`**，供 **City** 解析在线 `ActorRef`。
- **`CityBehavior`**：新增 **`SettlePlunderVictim`**；**pipeToSelf** 串联 **Registry Ask** → **Player Ask**；**City** 仅在 **Player 成功**后更新 **`CityMapCacheState`** 中 `battle-*` 键。
- **`WorldRegistry` / `Region` / `City`**：构造链注入 **`PlayerSessionRegistry`**（`WorldRegistryConfiguration` 已依赖 `playerSessionRegistry` Bean）。
- **新能力规格**：`game-plunder-settlement-ask-protocol`（含 **Pending / Committed / Failed** 状态说明；**不**单独引入短时 Settlement Actor，见 `design.md`）。

## Capabilities

### New Capabilities

- `game-plunder-settlement-ask-protocol`：Map→Player Ask、`battleId` 幂等、超时/失败语义、City 侧结算状态。

### Modified Capabilities

- 无（行为以新能力规格为主；既有 `game-player-session-actor` / `game-world-region-city-actors` 不删改主规格条款，避免重复归档冲突）。

## Impact

- **代码**：`game-service` 下 `pekko.session` / `pekko.world` 扩展；测试 `PlunderSettlementIntegrationTest`。
- **依赖**：无新 Maven 依赖。

## Dependencies

- 归档：`2026-04-14-add-world-region-and-city-actors`、`2026-04-14-add-player-session-actor`。

## Non-Goals

- **Reserve/Commit** 长行军（可选 change `add-battle-reserve-commit-player-wallet`）。
- **持久化** `battleId` 幂等表到 DB（阶段 6）。
- **短时 Settlement Actor（Saga）**：本 change **内联**于 **City** pipeline；若后续复杂化再拆 change。

## Risks

- **进程内**幂等：重启丢失；阶段 6 落库。
- **Player 离线**：Ask 失败 → `SettlementFailed`（已测）。

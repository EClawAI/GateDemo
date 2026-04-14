## Context

- **路线图** [`pekko-game-actor-openspec-roadmap.md`](../../../docs/pekko-game-actor-openspec-roadmap.md)：**资源权威在 Player**；地图触发 **Ask + `battleId` 幂等**。
- **现状**：[`CityBehavior`](../../../game-service/src/main/java/com/clawai/gatedemo/game/pekko/world/CityBehavior.java) 持有 **地图缓存**；[`PlayerSessionBehavior`](../../../game-service/src/main/java/com/clawai/gatedemo/game/pekko/session/PlayerSessionBehavior.java) 承载 **在线会话**。

## Goals / Non-Goals

**Goals:**

- **消息契约**：`SettlePlunder`（Player）、`GetPlayerSession`（Registry）、`SettlePlunderVictim`（City 入口）、`PlunderSettleResponse` / `PlunderSettlementResult`。
- **Player**：`battleId` **首次**扣减 `min(requested, wallet)` 并记录 **`battleId → actual`**；**重复** `battleId` → **`PlunderDuplicate`**（同 `actual`，**不**再扣）。
- **City**：**仅**在收到 **`PlunderOk` / `PlunderDuplicate`**（均视为已承诺）后写 **`battle-{id}`** 缓存键；**不**在 Player 之前写「已掠夺完成」语义。
- **超时**：`AskPattern` **5s**（`Duration`），失败 → `SettlementFailed`。
- **Settlement Actor**：**不**引入独立 Saga；逻辑在 **City** 内 **pipeToSelf** 异步链。

**Non-Goals:**

- **gRPC** 暴露、**proto** 变更。
- **Mongo/Outbox** 持久化（见 [`persistence-plan.md`](../../../docs/persistence-plan.md) 与阶段 6）。

## Decisions

### D1: 两阶段顺序（先 Player 再 Map）

1. **City** `Ask` **Registry** → **PlayerSession** `ActorRef`。
2. **City** `Ask` **Player** `SettlePlunder`。
3. **收到** `PlunderOk` / `PlunderDuplicate` → **更新** `CityMapCacheState`。

### D2: 幂等键

- **Player**：`battleId`（`long`），进程内 `ConcurrentHashMap<Long, Long>`。

### D3: 失败/超时

- **Registry** / **Player** **Ask** **失败** → **`PlunderSettlementResult.Failed(reason)`**；**不**写地图缓存。
- **Compensating**：**未**在 Player **提交**前写地图 → **无需**补偿；若未来 **先**写地图 **再** Ask，**必须** **Saga**（非本变更）。

### D4: 短时 Settlement Actor

- **不**创建；**理由**：当前 **两条** `pipeToSelf` 可测；**若** **In-flight** 数量爆炸再 **拆** `PlunderCoordinator`（后续 change）。

## 状态机（City 侧）

| 状态 | 含义 |
|------|------|
| **Pending** | 已收到 `SettlePlunderVictim`，尚未得到 Player 最终答复 |
| **Committed** | 收到 `PlunderOk` / `PlunderDuplicate`，已写 `battle-*` 缓存 |
| **Failed** | 离线/超时/拒绝，未写缓存 |

（**实现** 不显式枚举，**行为**与上表一致。）

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| 钱包与 Mongo 不一致 | 阶段 6 统一 wallet 与持久化 |
| 同 battle 并发两次 Ask | Player 邮箱串行 + 幂等表 |

## Migration Plan

- **部署**：纯代码扩展；**无**数据迁移。
- **回滚**：移除 `SettlePlunder` 命令与 City 管线；**保留** `GetPlayerSession` 可选（可后续复用）。

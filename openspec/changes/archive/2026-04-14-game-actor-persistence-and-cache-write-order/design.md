## 持久化边界

| 域 | 权威存储 | Actor 中的角色 |
|----|----------|----------------|
| 玩家经济 + `battleId` 结算记录 | Mongo **`player_data`**（`gold`、`plunderSettlements`） | **`PlayerSessionBehavior`** 仅串行处理 **`SettlePlunder`**，实际变更由 **`MongoPlayerPlunderLedger`** 在 **`PlayerDataManager.load` → 修改 → `saveNow`** 中完成 |
| 单城地图缓存占位 | Mongo **`city_world_state`**（`tiles` 映射） | **`CityBehavior`** 单写者；启动 **`loadSnapshot`**，变更后 **`saveSnapshot`** |

## 写序（与阶段 5 Ask 一致）

1. **City** `Ask` **Registry** → **Player** `SettlePlunder`。
2. **Player** 侧账本 **先** 在 Mongo 上完成 **扣款 + 幂等记录**（成功路径 **`PlunderOk`** / 重复路径 **`PlunderDuplicate`** 均不双扣）。
3. **City** 在收到 **`PlunderOk`/`PlunderDuplicate`** 后 **再** 更新 **`CityMapCacheState`** 并 **持久化城快照**。

若步骤 3 在进程崩溃前未完成：可能出现 **钱已扣、城未写战报键**；重启后 **重复** 同一 `battleId` 将得到 **Duplicate** 与相同 **`actual`**，City 可再次写入缓存（需业务层重试或幂等读）。**Outbox** 可将「Player 已提交」与「City 待写」同事务记录；本阶段不实现。

## 崩溃恢复

- **Player**：会话 Actor 内存不持久；重启后 **`PlayerDataManager.load`** 从 Mongo 恢复 **金币** 与 **`plunderSettlements`**。
- **City**：Region 内 City Actor 再创建时 **`loadSnapshot`** 回填 **`tiles`**。

## 测试策略

- Actor 单测使用 **`InMemoryPlayerPlunderLedger`**，不依赖 Mongo。
- 生产路径 **`MongoPlayerPlunderLedger`** / **`MongoCityWorldStatePersistence`** 由 Spring 注入；集成验证依赖运行中的 Mongo（本仓库默认单测不强制）。

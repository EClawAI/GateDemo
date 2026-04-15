## 1. 目标聚合与权威

```text
玩家 Actor（个人）     联盟 Actor（公会）     沙盘 Actor（单服·单玩法大地图）
       │                      │                         │
       │    Ask / 事件         │    联盟指令/事件         │  行军/格子/资源/战斗裁决入口
       └──────────────────────┴─────────────────────────┘
                              │
                    Worker 池（无状态，仅计算）
```

- **玩家**：钱包、背包、个人设置等 **Mongo `player_data`** 与会话一致性强相关字段的 **权威**（与现 `PlayerPlunderLedger` 一致）。
- **联盟**：联盟级数据 **权威**（存储选型：Redis/Mongo 在实现任务中定）。
- **沙盘**：**本玩法大地图** 上 **行军、格子占用、资源点、AOI 派生结果（写回沙盘）** 的 **唯一串行写入者**；**不**承担玩家钱包真源。

## 2. 为何用「单沙盘 Actor / 玩法」替代 Region/City 三级

- **写入边界集中**：大地图相关逻辑可在 **一个邮箱** 内顺序执行，减少「城 ↔ 行军 ↔ 矿」多 Actor 异步编排的日常编码成本。
- **与分服假设一致**：每 Game 服独立进程，**每玩法一张图** 一个 Actor 实例；**5k 在线** 下仍需 **压测** 验证峰值消息率。

**代价**：单点热点；**缓解**：`application.conf` 可配 **专用 dispatcher**、邮箱监控；若仍不足，**阶段 2** 将沙盘 **按战场/分线拆成多个沙盘 Actor**（仍少于「每格一 Actor」）。

## 3. Worker 模式（强制约束）

1. **Worker 不持有** 玩家/联盟/沙盘 **权威状态**。
2. **输入**：命令 ID + **只读快照**（或 DTO）+ 随机种子（若需要）。
3. **输出**：**结构化结果**（胜负、损耗、新位置列表等）。
4. **应用**：沙盘（或玩家）Actor 在邮箱内收到 Worker 回调消息后 **再** 修改内存与 **触发持久化**；**禁止** Worker 直接写 `PlayerDataManager` / Mongo。

实现可选：**`CompletableFuture` + `pipeToSelf`**，或 Spring **`@Async`** 注入 **仅调用纯函数** 的服务类。

## 4. 与现有掠夺 / 持久化协议的关系

- **`battleId` 幂等**、**Player 先扣款、沙盘再记战报** 的 **写序** 不变（见 `game-actor-persistence-and-cache-write-order`）。
- **调用链变更**：原 **`CityBehavior.SettlePlunderVictim`** → 改为 **`WorldMapSandboxBehavior`**（或等价类型）内发起 **`GetPlayerSession` + `SettlePlunder`**，成功后更新 **沙盘侧** 缓存/事件。

## 5. 迁移阶段（建议）

| 阶段 | 内容 |
|------|------|
| **M1** | 引入 **`WorldMapSandboxBehavior` 骨架** + Spring Bean；**特性开关** 默认关；**单元测试** 消息契约。 |
| **M2** | 将 **掠夺结算** 从 **City** 迁到 **沙盘**；**City** 路径标记 `@Deprecated` 或删除（视测试覆盖）。 |
| **M3** | **联盟 Actor** 最小实现（创建/解散占位 + Redis/Mongo）。 |
| **M4** | **玩家 Actor** 与 **`PlayerSessionBehavior` 命名/职责** 对齐文档与代码（是否合并离线邮箱另立 change）。 |
| **M5** | 移除 **World/Region/City** 空壳或改为 **转发适配器**（兼容期后删除）。 |

## 6. 消息命名（示例，实现时可调整）

- 沙盘 → 玩家：`RequestPlunderSettle(battleId, victimId, requested, replyTo)`（内部仍用现有 `SettlePlunder`）。
- 玩家 → 沙盘：`MarchCommand`、`GatherCommand`（具体 proto/内部 record 在实现任务中定义）。

## 7. 观测

- 沙盘 Actor：**邮箱深度**、处理耗时直方图；Worker：**队列长度**、任务失败率。
- 与路线图 **阶段 7**（观测）对齐时可合并仪表盘。

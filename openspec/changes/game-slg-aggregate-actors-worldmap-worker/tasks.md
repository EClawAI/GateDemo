## 1. 规格与设计冻结

- [ ] 1.1 评审本 `proposal.md` / `design.md`，确认 **玩法键（gameplayId）** 与 **沙盘 Actor 实例键**（如 `serverId + gameplayId`）
- [ ] 1.2 在 `tasks.md` 勾选后，执行 `openspec validate --type change game-slg-aggregate-actors-worldmap-worker`（或等价校验）

## 2. M1 — 沙盘 Actor 骨架

- [ ] 2.1 新增 `WorldMapSandboxBehavior`（或团队选定命名）+ 密封 `Command` 占位
- [ ] 2.2 `WorldMapSandboxConfiguration`：经 `SpawnProtocol` 注册 `ActorRef`，与 `PlayerSessionRegistry` 一样注入
- [ ] 2.3 特性开关（如 `game.slg.sandbox-enabled`）默认 **false**
- [ ] 2.4 单测：spawn 后可 `tell` ping，无业务依赖 Mongo

## 3. M2 — 掠夺路径迁移

- [ ] 3.1 将 **掠夺 Ask** 从 `CityBehavior` 迁至 **沙盘**（或开关切换双路径）
- [ ] 3.2 更新 `PlunderSettlementIntegrationTest` / 相关测试
- [ ] 3.3 更新 `openspec/specs` 中与 City 强绑定的条款（归档时同步）

## 4. M3 — 联盟 Actor

- [ ] 4.1 `AllianceBehavior` + `AllianceRegistryBehavior`（按 `allianceId` 路由）最小实现
- [ ] 4.2 持久化与 **联盟 Actor 权威** 字段清单（文档 + 代码注释）

## 5. M4 — 玩家聚合对齐

- [ ] 5.1 文档化 **PlayerSession** 与「每玩家一聚合」关系；可选重命名类（大 PR 时单独立项）
- [ ] 5.2 确认 **离线** 是否需 **Mailbox** / Sharding（非本 change 强制）

## 6. M5 — 移除旧世界层级

- [ ] 6.1 删除或适配 `WorldRegistryBehavior`、`RegionBehavior`、`CityBehavior`
- [ ] 6.2 `WorldRegistryConfiguration` → 沙盘配置；无死 Bean
- [ ] 6.3 更新 [`docs/pekko-game-actor-openspec-roadmap.md`](../../docs/pekko-game-actor-openspec-roadmap.md) 阶段 4/5 的 **实现说明** 链接

## 7. Worker 基础设施

- [ ] 7.1 定义 **纯函数** 计算接口 + **回投邮箱** 模板（示例：伪战斗结算）
- [ ] 7.2 配置 **线程池**（与 Pekko default dispatcher 隔离说明）

## 8. 归档

- [ ] 8.1 `openspec archive` 合并 delta spec 至 `openspec/specs/game-slg-aggregate-actors-worldmap-worker/`
- [ ] 8.2 声明与 `game-world-region-city-actors` 的 **替代/并存** 关系

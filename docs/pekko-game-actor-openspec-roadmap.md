# Game 侧 Pekko Actor 实施路线图（OpenSpec 提案索引）

本文档汇总既有讨论结论，并拆成**可按顺序立项**的 OpenSpec 变更（`openspec new change "<kebab-name>"` → `proposal.md` / `design.md` / `tasks.md` 及所需 `specs/`）。  
目标：在 **game 服务**内引入 **Apache Pekko Typed Actor**，与现有 **Gate–Game gRPC（偏流式）**、**地图缓存 + DB**、**SLG 城/玩家资源** 模型对齐；**不替代** Gate 对外协议层。

**相关笔记**：[`actor_study.md`](actor_study.md)（自学与 Pekko 文档索引）。

---

## 1. 架构原则（后续提案不得违背）

| 原则 | 说明 |
|------|------|
| **Gate 与 Game 边界** | Gate 负责客户端长连与转发；Game 承载主业务。**Game 侧 Actor 不对外取代 gRPC**。 |
| **会话 vs 世界** | **PlayerSessionActor**（在线会话）≠ **世界权威**（城/地格/分区）。离线玩家不必常驻 Session Actor。 |
| **SLG 聚合边界** | 大地图侧写入收敛为 **沙盘 Actor**（`WorldMapSandboxBehavior`）；城内逻辑以 `cityId` / `targetCityId` 区分。**玩家**在模型中常以 `ownerId` / `playerId` 引用。 |
| **资源权威** | 资源读写在 **PlayerActor（或等价钱包聚合）** 内串行完成；**掠夺等由地图触发**时，采用 **请求–响应（Ask / 带 reply）+ `battleId` 幂等**，**最终可掠夺量以 Player 提交为准**，避免地图副本与钱包双真源。 |
| **地图缓存 + DB** | 对同一城/分区：**单一写入者**顺序更新内存态与持久化策略（同步/异步/Outbox 在提案中选定）；禁止「地图先扣、Player 再对账」导致不一致。 |
| **线程模型** | Netty / gRPC 回调线程：**只做入队**；阻塞 IO/睡眠不得占用 Actor 默认 dispatcher（必要时常用隔离 dispatcher 或 `pipeToSelf`）。 |
| **规模假设** | 峰值在线可在万级以内演进；Actor 数量本身通常不是首要瓶颈，**消息率、邮箱堆积、大对象状态** 是压测重点。 |

---

## 2. 与仓库内既有 OpenSpec 能力的关系（立项前扫一眼）

以下规格/历史变更可能与新提案重叠，**新开 change 时在 proposal 里注明「衔接或替代」**：

- `openspec/specs/core-message-framework/`、`game-handler-layer/`：游戏消息处理分层。
- `openspec/specs/capability-grpc-stream/` 及 `archive/...-unified-message-handler`：Gate–Game 流式通信。
- `openspec/changes/change-game-connection-pool`、`change-gate-game-grouping`：连接与分组。
- `openspec/specs/data-persistence-core/`、`persistence-plan.md`：若 Actor 落库/Outbox 与现有一致性策略相关。

---

## 3. 分阶段路线图（每个阶段 = 一至多个 OpenSpec change）

建议 **自上而下**：先 **运行时与消息契约**，再 **世界模型**，再 **跨 Actor 结算协议**，最后 **集群/可观测性**（按需）。

---

### 阶段 0 — 决策冻结与基线

**目的**：把技术选型与依赖管理方式写死，避免各提案版本漂移。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`pekko-game-baseline-and-bom`](../openspec/changes/archive/2026-04-14-pekko-game-baseline-and-bom/proposal.md)（能力：[`openspec/specs/pekko-game-baseline/spec.md`](../openspec/specs/pekko-game-baseline/spec.md)） | `proposal.md`：明确 Pekko 版本线、BOM、`pekko-actor-typed`、后续可选模块（`pekko-stream`、`pekko-remote`/`cluster-*`）的**引入顺序**；与现有 **Java 版本**、构建工具对齐。`design.md`：模块边界图（Game JAR 内 ActorSystem 单例策略、与 Spring/纯 Main 二选一）。 |

**验收**：团队能指着一份文档说出「第一版不引入 Cluster」或「何时引入 Sharding」。

---

### 阶段 1 — Game 内嵌入 ActorSystem 与通用消息外壳

**目的**：进程内可用 Typed Actor；本阶段**不承载**完整玩法，只验证 **生命周期 + 消息 + 序列化占位**。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`integrate-pekko-actor-system-in-game-service`](../openspec/changes/integrate-pekko-actor-system-in-game-service/proposal.md) | `design.md`：`ActorSystem` 创建/关闭（与 JVM shutdown hook、协调停止）；配置入口（`application.conf` / HOCON）；本地测试策略。`tasks.md`：最小 Main 或 Spring `Bean` 集成 PoC。 |
| `define-game-actor-message-envelope` | **跨聚合消息**（gate/game 已有 proto 之外的 **Actor 内部**）：信封结构（`correlationId` / `battleId` 预留位）、错误承载、版本号；是否与现有 core-message 框架 ID 对齐。可选：`specs/.../spec.md` 描述不变量。 |

**验收**：单测或集成测试可 `spawn` 父子 Actor，`tell` 往返；无业务逻辑亦可。

---

### 阶段 2 — Gate/流式 gRPC → Game Actor 的桥接（入站）

**目的**：把 **已有 gRPC stream** 上的帧**不阻塞 IO 线程**地导入 Actor。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`bridge-grpc-stream-to-game-actor-mailbox`](../openspec/changes/archive/2026-04-14-bridge-grpc-stream-to-game-actor-mailbox/proposal.md)（能力：[`openspec/specs/game-grpc-stream-actor-bridge/spec.md`](../openspec/specs/game-grpc-stream-actor-bridge/spec.md)） | `design.md`：从 gRPC stub 到 `ActorRef` 的映射（按 `sessionId` / `playerId`）；背压（`StreamObserver` / reactive 适配与「丢弃/限流」策略二选一）；与 [`backpressure-design.md`](backpressure-design.md) 关系。`tasks.md`：最小 E2E（mock client–game）。 |

**验收**：压测脚本下 IO 线程无长时间占用；消息可达指定 Session Actor。

---

### 阶段 3 — PlayerSessionActor（在线一人一 Actor）

**目的**：**仅在线会话**绑定的 Player Actor：收客户端意图、改钱包、发下游命令；**不负责**整张地图权威。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`add-player-session-actor`](../openspec/changes/archive/2026-04-14-add-player-session-actor/proposal.md)（能力：[`openspec/specs/game-player-session-actor/spec.md`](../openspec/specs/game-player-session-actor/spec.md)） | `design.md`：生命周期（登录创建/断线停止/重连合并策略）；邮箱与状态上限；与 **UserId** 寻址。`specs/`：会话 Actor 对外（向 World）发出的 **命令类型表**（初版可只有占位）。 |

**验收**：connect/disconnect 无资源泄漏；父监督策略文档化。

---

### 阶段 4 — 世界模型：沙盘 Actor 与路由键

**目的**：落地 **「每服 × 每玩法单沙盘 + 玩家仅 ID」**；地图缓存归属写入者在 **单沙盘邮箱** 内定义；逻辑上仍用 **`persistenceRegionId` + `cityId`** 作城快照键。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| 历史：[`add-world-region-and-city-actors`](../openspec/changes/archive/2026-04-14-add-world-region-and-city-actors/proposal.md)（能力：[`openspec/specs/game-world-region-city-actors/spec.md`](../openspec/specs/game-world-region-city-actors/spec.md)） | 规格已演进为 **仅 `WorldMapSandboxBehavior`**（无 World/Region/City 多级 Actor）。 |
| [`game-slg-aggregate-actors-worldmap-worker`](../openspec/changes/archive/2026-04-15-game-slg-aggregate-actors-worldmap-worker/proposal.md)（能力：[`openspec/specs/game-slg-aggregate-actors-worldmap-worker/spec.md`](../openspec/specs/game-slg-aggregate-actors-worldmap-worker/spec.md)） | 实例键：`game.id` + `game.slg.gameplay-id`；代码入口 `WorldMapSandboxBehavior` / `WorldMapSandboxConfiguration`。 |

**验收**：单测沙盘邮箱内命令顺序可观测；`PlunderSettlementIntegrationTest` 覆盖 Map→Player Ask。

---

### 阶段 5 — 掠夺/结算：Map → Player Ask 与幂等（核心一致性）

**目的**：实现讨论确定的 **两阶段语义**：`battleId`、Player 计算并扣减、回复 `actual`、沙盘再写战报/地图占位。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`add-plunder-settlement-ask-protocol`](../openspec/changes/archive/2026-04-14-add-plunder-settlement-ask-protocol/proposal.md)（能力：[`openspec/specs/game-plunder-settlement-ask-protocol/spec.md`](../openspec/specs/game-plunder-settlement-ask-protocol/spec.md)） | `design.md`：消息契约（`SettlePlunder`、`PlunderCommitted`、失败/超时）；幂等与重试表；是否与 **短时 Settlement Actor**（Saga）同 change 或子任务拆分。`specs/`：**状态机**（Pending / Committed / Compensating）。 |
| （可选）`add-battle-reserve-commit-player-wallet` | 若玩法存在 **长行军/围攻**，引入 **Reserve / Commit**；依赖 `add-plunder-settlement-ask-protocol`。 |

**验收**：混沌测试：Player 超时、重复 `battleId`、并发花费资源；最终无「地图已结、钱未扣」或反向双扣。

**实现说明**：掠夺 Ask 由 **`WorldMapSandboxBehavior.SettlePlunderVictim`** 发起；写序与 `battleId` 幂等不变。集成测试见 `game-service` 内 `PlunderSettlementIntegrationTest`。

---

### 阶段 6 — 持久化、缓存回填与 Outbox（若需强一致）

**目的**：DB 与地图缓存 **可恢复**；跨 Actor 与 DB **可审计**。

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| [`game-actor-persistence-and-cache-write-order`](../openspec/changes/archive/2026-04-14-game-actor-persistence-and-cache-write-order/proposal.md)（能力：[`openspec/specs/game-actor-persistence-and-cache-write-order/spec.md`](../openspec/specs/game-actor-persistence-and-cache-write-order/spec.md)） | `design.md`：写序、崩溃恢复、Outbox/事件表（可选）；Player vs 沙盘城持久化边界。 |

**验收**：进程 kill 后重启，城态与钱包可与验收用例对齐。

---

### 阶段 7 — 观测、调优与防护

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| `pekko-game-observability-mailbox-metrics` | 邮箱深度、dead letters、`ask` 超时计数；日志 `correlationId`/`battleId`；可选 OpenTelemetry。 |
| `pekko-game-dispatchers-and-blocking-isolation` | 固定 blocking/cached pool 用于 JDBC 等；编码规范检查清单。 |

---

### 阶段 8 — 分布式（**按需**，第一版可明确「不做」）

| 建议 change 名称 | 交付物要点 |
|------------------|------------|
| `pekko-cluster-sharding-for-world-entities` | `entityId` = `cityId` 等；passivation；与 gRPC 入口机关系。 |
| `location-transparent-guild-or-remote-actors` | 联盟等：**同进程 ActorRef vs 远端** 的端口-适配器；可与独立 gRPC 服务并存。 |

**依赖**：阶段 0 中已声明的 Cluster 引入条件（节点数、运维）。

---

## 4. 建议 OpenSpec 立项顺序（依赖图）

```text
阶段0 baseline
    ↓
阶段1 ActorSystem + 消息外壳
    ↓
阶段2 gRPC stream 桥接 ──→ 阶段3 PlayerSessionActor
    ↓                            ↓
        阶段4 沙盘/路由 ──→ 阶段5 掠夺 Ask/幂等
                                ↓
                        阶段6 持久化/缓存（可选并行设计）
                                ↓
                        阶段7 观测与 dispatcher
                                ↓
                        阶段8 Cluster（按需）
```

**说明**：阶段 2 与 3 可并行，但 **阶段 4 应在 5 之前**冻结路由与缓存归属；**阶段 5** 依赖 **Player** 与 **沙盘（地图侧）** 至少具备桩实现。

---

## 5. 每个 OpenSpec change 的建议目录检查清单

新建 `openspec/changes/<name>/` 后，建议在 `proposal.md` 中固定包含：

1. **Why**：对应本路线图哪一条原则 / 哪一业务痛点。  
2. **What Changes**：用户可见行为与模块边界。  
3. **Non-Goals**：本变更明确不做的事（避免范围爬行）。  
4. **Risks**：一致性、性能、运维；回滚策略。  
5. **Dependencies**：依赖哪些既有 change / spec。

`design.md` 建议含：**消息序图（mermaid）**、**故障场景表**、**与 Pekko 文档章节对应**（便于 code review）。

---

## 6. 后续你可以如何使用本文

1. 从 **阶段 0** 起执行：`openspec new change "pekko-game-baseline-and-bom"`（名称可按团队习惯微调，保持 kebab-case）。  
2. 每完成一个 change，在本文对应行打勾或链接到 `openspec/changes/<name>/proposal.md`。  
3. 若某一阶段需拆分（例如「沙盘」与「联盟」分两个 change），在阶段 4 下增加子行即可，**不必改写原则章节**。

---

## 7. 修订记录

| 日期 | 说明 |
|------|------|
| 2026-04-08 | 初版：基于 Gate/Game gRPC、SLG 城/玩家资源、掠夺 Ask+`battleId`、地图缓存单写者等讨论整理。 |

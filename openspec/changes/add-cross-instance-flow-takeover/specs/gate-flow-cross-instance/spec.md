# gate-flow-cross-instance — Delta

## ADDED Requirements

### Requirement: Lua 原子跨实例 owner 迁移

`RedisFlowStore` SHALL 提供 `crossTakeover(flowId, newOwnerGateId, newExpiresAtMs)` 入口，底层使用 `lua/flow_cross_takeover.lua` 单脚本原子完成：

1. 读取 `gate:flow:<flowId>` Hash；
2. 若 hash 不存在或 `expiresAt <= now` → 返回 `EXPIRED`；
3. 否则 `HSET ownerGateId/expiresAt` 并 `PEXPIRE` flow hash 与对应 byplayer 索引；
4. 返回 **改写前的** `ownerGateId`（若与新值一致则返回字符串 `SAME`）。

返回值 SHALL 包装为 `CrossTakeoverResult(boolean ok, String previousOwnerGateId, State state)`，其中 `state ∈ {RESUMED_OWNER_SAME, RESUMED_OWNER_CHANGED, EXPIRED, REDIS_UNAVAILABLE}`。

#### Scenario: owner 一致（幂等）

- **GIVEN** Redis `gate:flow:<flowId>` 当前 `ownerGateId=gate-02`，未过期
- **WHEN** gate-02 调用 `crossTakeover(flowId, "gate-02", ...)`
- **THEN** `state` SHALL = `RESUMED_OWNER_SAME`
- **AND** `previousOwnerGateId` SHALL = `"gate-02"`
- **AND** Redis 中 `expiresAt` SHALL 被更新

#### Scenario: owner 切换

- **GIVEN** Redis flow hash `ownerGateId=gate-01`，未过期
- **WHEN** gate-02 调用 `crossTakeover(flowId, "gate-02", ...)`
- **THEN** `state` SHALL = `RESUMED_OWNER_CHANGED`
- **AND** `previousOwnerGateId` SHALL = `"gate-01"`
- **AND** Redis 中 `ownerGateId` SHALL 已被改写为 `"gate-02"`

#### Scenario: flow 已过期

- **GIVEN** Redis flow hash `expiresAt < now`
- **WHEN** 任一 gate 调用 `crossTakeover(...)`
- **THEN** `state` SHALL = `EXPIRED`
- **AND** Redis 中 `ownerGateId` SHALL **NOT** 被改写

### Requirement: Pub/Sub `gate:flow:evict` 通道

`gate-service` SHALL 在以下时机发布 `gate:flow:evict` 事件：

- `FlowSessionManager.resume(...)` 调用 `crossTakeover` 返回 `RESUMED_OWNER_CHANGED` 之后；
- payload SHALL 为 UTF-8 JSON：`{"flowId": "...", "newOwnerGateId": "...", "evictedAt": <epochMillis>}`；
- 通道名通过 `gate.flow.cross.evict-channel`（默认 `gate:flow:evict`）配置。

`gate-service` SHALL 在启动时通过 `RedisMessageListenerContainer` 订阅该通道。订阅处理器 SHALL：

- 解析 JSON；解析失败 → emit `gate_flow_cross_evict_invalid_total` 并跳过；
- 若 `newOwnerGateId` 等于本实例 `gate.id` → 跳过（无需驱逐自己）；
- 否则调用 `FlowSessionManager.evictByCrossInstanceTakeover(flowId, newOwnerGateId)`。

#### Scenario: 老 gate 收到 evict 关闭本地 Channel

- **GIVEN** gate-01 本地 `flowsById` 含 `flowId=X`，state=ATTACHED，channel=ch1
- **AND** gate-02 已通过 `crossTakeover` 成为新 owner 并发布 evict
- **WHEN** gate-01 的 listener 处理该事件
- **THEN** gate-01 本地 `flowsById` SHALL NOT 再含 `flowId=X`
- **AND** `ch1.isOpen()` SHALL = `false`
- **AND** gate-01 SHALL NOT 写 Redis（不调用 `markDetached` / `destroy`）
- **AND** metric `gate_flow_cross_evict_received_total` SHALL +1
- **AND** 结构化日志 SHALL 输出 `event=flow.evict.received flowId=X from=gate-01 to=gate-02`

#### Scenario: 老 gate 收到的 evict 指向自己

- **GIVEN** gate-02 误监听并收到一条 `newOwnerGateId=gate-02` 的事件
- **WHEN** listener 处理
- **THEN** SHALL 跳过（不调用任何 manager 方法）
- **AND** metric `gate_flow_cross_evict_received_total{ignored=self}` SHALL +1（标签）

### Requirement: RESUME 路径替换 REJECTED_OWNER_OTHER 早退

`FlowSessionManager.resume(flowId, playerId, lastClientRecvSeq, channel, clientFeatures)` 在 `record.ownerGateId() != self` 时 SHALL NOT 直接返回 `REJECTED_OWNER_OTHER`，而是 SHALL 执行：

1. 调用 `redisStore.crossTakeover(flowId, self.gateId, newExpiresAt)`；
2. 根据返回 `state`：
   - `RESUMED_OWNER_SAME` / `RESUMED_OWNER_CHANGED` → 继续 Phase A 的 attach + B1 features 协商；`RESUMED_OWNER_CHANGED` 时 SHALL 通过 `FlowEvictPublisher` 发布 evict 事件；
   - `EXPIRED` → 返回 `REJECTED_EXPIRED`；
   - `REDIS_UNAVAILABLE` → 返回 `REJECTED_OWNER_OTHER`（保守兜底，防止 split-brain）。

#### Scenario: 跨实例 RESUME 成功

- **GIVEN** Redis flow hash `ownerGateId=gate-01`，未过期，`playerId=42`
- **AND** gate-02 收到 `AuthRequest{flow_id=..., player_id=42}` 后通过 token 校验
- **WHEN** gate-02 的 `FlowSessionManager.resume(...)` 被调用
- **THEN** 返回 `ResumeResult.outcome` SHALL = `RESUMED`
- **AND** Redis 中 `ownerGateId` SHALL = `"gate-02"`
- **AND** gate-02 SHALL 发布一条 `gate:flow:evict` 消息含 `flowId, newOwnerGateId=gate-02`
- **AND** metric `gate_flow_cross_takeover_total{outcome=owner_changed}` SHALL +1

#### Scenario: Redis 不可用兜底

- **GIVEN** Redis 不可达（`crossTakeover` 抛 `DataAccessException`）
- **AND** 本地缓存仍记 `ownerGateId=gate-other`
- **WHEN** `resume(...)` 调用
- **THEN** SHALL 返回 `REJECTED_OWNER_OTHER`
- **AND** metric `gate_flow_cross_takeover_total{outcome=redis_unavailable}` SHALL +1
- **AND** 上层 handler SHALL 自动降级 NEW（保留 Phase A 行为）

### Requirement: 本地驱逐方法 `evictByCrossInstanceTakeover`

`FlowSessionManager` SHALL 暴露 `evictByCrossInstanceTakeover(String flowId, String newOwnerGateId)`，作为 Pub/Sub listener 的唯一入口。该方法 SHALL：

- 从 `flowsById` / `flowIdByPlayer` 移除目标 flow（带 owner guard，仅在 byplayer 当前仍指向该 flow 时移除）；
- 若 ATTACHED 且 channel.isActive() → 关闭 channel（驱逐过时连接）；
- emit `event=flow.destroyed reason=cross_takeover newOwnerGateId=<newOwnerGateId>`；
- SHALL NOT 写 Redis（避免与新 owner 竞态）。

#### Scenario: 仅清本地、不动 Redis

- **GIVEN** gate-01 本地有 `flowId=X` ATTACHED
- **AND** Redis 中该 flowId 的 `ownerGateId` 已被新 gate 改写
- **WHEN** `evictByCrossInstanceTakeover(X, "gate-02")` 被 listener 调用
- **THEN** gate-01 本地 SHALL 不再持有 `flowId=X`
- **AND** Redis flow hash 内容 SHALL 不被修改
- **AND** SHALL emit metric `gate_flow_total{event=destroyed,reason=cross_takeover}` +1

### Requirement: 跨实例迁移可观测性

`gate-service` SHALL 暴露以下 metrics：

- `gate_flow_cross_takeover_total{outcome}`：outcome ∈ {`owner_same`, `owner_changed`, `expired`, `redis_unavailable`}；
- `gate_flow_cross_evict_published_total`；
- `gate_flow_cross_evict_received_total{ignored?}`（标签 `ignored` 可选值 `self|unknown|invalid`，默认无标签表示成功处理）；
- `gate_flow_cross_evict_invalid_total`。

结构化日志 SHALL 至少包含 `flow.cross_takeover`、`flow.evict.published`、`flow.evict.received`，字段含 `flowId, previousOwnerGateId, newOwnerGateId, latencyMs`。

#### Scenario: 跨实例 RESUME 触发完整观测链

- **GIVEN** gate-01 持有 flowId=X，gate-02 接到 RESUME
- **WHEN** RESUME 完成
- **THEN** gate-02 SHALL 至少出现：
  - `gate_flow_cross_takeover_total{outcome=owner_changed} +1`
  - `gate_flow_cross_evict_published_total +1`
  - 日志 `event=flow.cross_takeover newOwnerGateId=gate-02 previousOwnerGateId=gate-01 ...`
- **AND** gate-01 SHALL 至少出现：
  - `gate_flow_cross_evict_received_total +1`
  - 日志 `event=flow.evict.received flowId=X to=gate-02`

### Requirement: 跨实例 RESUME 后 buffer 冷启动语义

跨实例 RESUME 后，新 gate 上的 `FlowSession.buffer` SHALL 为空（B1 在 ATTACHED 时按 features 懒装）。`replayPending(...)` 在该会话上 SHALL 返回 `count=0`，且不输出 `event=flow.buffer.replay`（避免误导）。

#### Scenario: 跨实例 RESUME 不产生 replay 日志

- **GIVEN** 跨实例 RESUME 流程
- **WHEN** `FlowSessionManager.resume(...)` 完成
- **THEN** 该次 RESUME SHALL NOT 在日志中输出 `event=flow.buffer.replay`
- **AND** `ResumeResult.replayedCount` SHALL = 0
- **AND** metric `gate_flow_buffer_replay_total` SHALL 不被本次 RESUME 增加

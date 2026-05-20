## Why

Phase A 把 `ownerGateId` 写进 Redis flow hash，但当客户端 RESUME 时落到「非原 owner」的 gate 实例上，会被 **直接拒绝 `REJECTED_OWNER_OTHER`** 后降级为 NEW。该兜底在多实例 + LB 漂移 / gate 实例滚动重启的真实场景下非常容易触发，造成「明明刚 60s 前还在线」的用户被强制走 NEW 流程，丢失 FlowSession 状态（B1 下行 buffer、未读消息锚点等）。

参考 Gateway2 的「跨实例迁移」语义，本变更让 **新 gate 在 Redis 中原子拿到 owner**，并通过 **Redis Pub/Sub 通知老 gate 让出（驱逐本地 FlowSession + 关 Channel）**。具体地：

1. 新 gate 收到带 `flow_id` 的 RESUME 请求 → 调用新的 Lua 脚本 `flow_cross_takeover.lua`，原子完成「ownerGateId 改写 + 续 TTL + 返回旧 owner」；
2. 若返回的旧 owner ≠ 当前 gate id，**新 gate 发布 `gate:flow:evict {flowId, newOwnerGateId}` 事件**；
3. 老 gate 的 `FlowEvictListener` 收到事件后：从本地 `flowsById` 移除该 flow（不写 Redis），并关闭其当前 Channel（若仍 ATTACHED）；
4. 新 gate 完成 channel 绑定，按 Phase A / B1 的常规 RESUME 流程返回 `RESUMED`。

此外，B1 的下行 buffer 在跨实例 RESUME 后是「冷的」（新 gate 没有历史 entry）—— 本变更采用 **保守策略**：跨实例 RESUME 仅打 metric `cross_instance_warm_buffer_miss`，不再尝试拉取 Redis 中的离线消息；B3 会进一步处理「flow buffer 失效 → 走离线流」的合流策略。

## What Changes

### Redis 层

- 新增 Lua `lua/flow_cross_takeover.lua`：参数 `(flowId, newOwnerGateId, newExpiresAtMs, ttlMs)`，原子地：
  1. `HGET flow hash`；
  2. 若不存在或 `expiresAt <= now` → 返回 `EXPIRED`；
  3. 否则 `HSET ownerGateId, expiresAt`、`PEXPIRE flow hash`、`PEXPIRE byplayer`；
  4. 返回旧 `ownerGateId`（若与新一致，则返回字符串 `SAME`）。

- `RedisFlowStore` 新方法 `CrossTakeoverResult crossTakeover(String flowId, String newOwnerGateId, long newExpiresAtMs)`：record `(boolean ok, String previousOwnerGateId, String state)` —— `state ∈ {RESUMED_OWNER_SAME, RESUMED_OWNER_CHANGED, EXPIRED, REDIS_UNAVAILABLE}`。

### Pub/Sub 通道

- 频道名：`gate:flow:evict`（由配置 `gate.flow.cross.evict-channel` 可改），payload：JSON `{"flowId":"...", "newOwnerGateId":"...", "evictedAt":<ms>}`。
- 发布方：`FlowEvictPublisher`（薄封装 `StringRedisTemplate#convertAndSend`）。
- 订阅方：`FlowEvictListener` 实现 `MessageListener`，由 `FlowEvictPubSubConfig` 注册到 `RedisMessageListenerContainer`。
  - 收到事件且 `newOwnerGateId == self.gateId` 时 **忽略**（自己发的，无需驱逐）；
  - 其他情况：调用 `FlowSessionManager.evictByCrossInstanceTakeover(flowId, newOwnerGateId)` —— 从本地索引移除、关闭 ATTACHED Channel、emit `flow.destroyed reason=cross_takeover` 事件，**不** 写 Redis（Redis 已被新 owner 改写）。

### FlowSessionManager

- 删除「`record.ownerGateId() != self → REJECTED_OWNER_OTHER` 早退」分支；改为执行 `crossTakeover` 流程：
  - `RESUMED_OWNER_SAME`：走 Phase A 原路径；
  - `RESUMED_OWNER_CHANGED`：emit `cross_takeover_initiated` metric，发布 evict 事件，再走 Phase A attach + B1 features 协商；
  - `EXPIRED`：与 Phase A 一致返回 `REJECTED_EXPIRED`；
  - `REDIS_UNAVAILABLE`：保守起见仍返回 `REJECTED_OWNER_OTHER`（避免 split-brain）。
- 新增 `evictByCrossInstanceTakeover(flowId, newOwnerGateId)`：仅清本地内存与关 Channel，不动 Redis。
- B1 状态迁移：跨实例 RESUME 后 buffer 是新的（manager 已按 B1 协商策略懒装），不主动拉取旧实例的 buffer（不可行 — buffer 仅存内存）。

### 配置

- `gate.flow.cross.enabled`（默认 `true`）：关闭后退回 Phase A 的 `REJECTED_OWNER_OTHER` 早退；
- `gate.flow.cross.evict-channel`（默认 `gate:flow:evict`）；
- `gate.flow.cross.publish-self-evict`（默认 `false`）：调试用，是否发布 self → self 的 evict（一般无意义）。

### 协议

- 不改 `proto`；`ResumeStatus` 现有枚举已经包含 `RESUMED` 与 `REJECTED_OWNER_OTHER`，本变更只是让前者覆盖更多场景。

### 可观测性

- metrics 新增：`gate_flow_cross_takeover_total{outcome}` (`owner_same|owner_changed|expired|redis_unavailable`)；`gate_flow_cross_evict_received_total`；`gate_flow_cross_evict_published_total`。
- 结构化日志：`event=flow.cross_takeover initiator=...` / `event=flow.evict.received from=... to=...`。

## Capabilities

### New Capabilities

- `gate-flow-cross-instance`：跨实例 owner 迁移协议；Lua `flow_cross_takeover.lua`；Pub/Sub `gate:flow:evict` 通道；以及对 Phase A `REJECTED_OWNER_OTHER` 早退分支的语义性替换（保留同名枚举值仅用于「Redis 不可用」回退）。

### Implicitly affected

- `gate-resume-reconnect`（Phase A 未归档）：`REJECTED_OWNER_OTHER` 的触发条件被收紧到 **`crossTakeover` 返回 `REDIS_UNAVAILABLE`**；归档后由后续整合 change 显式 MODIFY。
- `gate-flow-downstream-buffer`（B1）：跨实例 RESUME 时 buffer 必然为空，B1 的 `replayedCount` 字段在该场景下记 0，配合 metric `gate_flow_cross_takeover_total{outcome=owner_changed}` 排查。

## Impact

- **代码**：`RedisFlowStore` 新增 `crossTakeover`；新 `FlowEvictPublisher` / `FlowEvictListener` / `FlowEvictPubSubConfig` 三类；`FlowSessionManager.resume` 改造一处分支；新 Lua `flow_cross_takeover.lua` 文件。
- **协议**：无改动。
- **运维**：Redis Pub/Sub 流量极小（每次跨实例 RESUME 一条消息），但需要确认 Redis 6+/Sentinel 配置支持 `SUBSCRIBE`；如使用 Redis Cluster 需注意 keyspace 分片（本变更使用 fixed channel 名，非 keyspace 通知，分片透明）。
- **性能**：跨实例 RESUME 多一次 Lua 调用 + 一次 PUBLISH，延迟 +~2ms（量化在 design）。

## Non-Goals

- **不** 迁移老 gate 上的 FlowSession 内存状态到新 gate（包括 B1 buffer）；新 gate 视为「从 Redis 重建」。
- **不** 做 cluster-wide eviction 的 strict consistency 保证（事件丢失会导致老 gate 上的 Channel 短暂残留，靠后续 channel.write 失败感知）。
- **不** 引入 ZooKeeper / etcd 等额外分布式协调（仅复用现有 Redis）。
- **不** 改 game-service / login-service。

## 1. 跨实例 RESUME 数据流

```text
                           ┌──────────────── client ────────────────┐
                           │  (AUTH with flow_id, last_recv_seq)    │
                           └─────────────────┬──────────────────────┘
                                             │
                                             ▼
        ┌────────────────────────── gate-B (new owner) ─────────────────────────┐
        │ 1. resume(flowId,...)                                                 │
        │ 2. RedisFlowStore.loadByFlowId → record(playerId, ownerGateId=gate-A) │
        │ 3. RedisFlowStore.crossTakeover(flowId, newOwner=gate-B, newExp)      │
        │       Lua atomic: HGET → HSET ownerGateId/expiresAt → PEXPIRE         │
        │       returns previous = "gate-A"                                     │
        │ 4. FlowSessionManager.evictLocal (no-op, not on this gate)            │
        │ 5. FlowEvictPublisher.publish(flowId, gate-B)                         │
        │ 6. Phase A 路径：attach channel, B1 features 协商, replay buffer (空)│
        │ 7. AuthResponse{resume_status=RESUMED, server_features=...}           │
        └─────────────────────────────────┬─────────────────────────────────────┘
                                          │
                                          ▼  PUBLISH gate:flow:evict
                ┌──────── gate-A (previous owner) ────────┐
                │ FlowEvictListener.onMessage              │
                │   if newOwnerGateId == self → ignore     │
                │   else manager.evictByCrossInstanceTakeover(flowId, gate-B)
                │     · remove from flowsById              │
                │     · close ATTACHED channel (if any)    │
                │     · emit metric / log                  │
                │     · DO NOT touch Redis                 │
                └──────────────────────────────────────────┘
```

## 2. Lua 脚本 `flow_cross_takeover.lua`

```lua
-- KEYS[1] = flowKey (gate:flow:<flowId>)
-- KEYS[2] = byPlayerKey (gate:flow:byplayer:<playerId>)
-- ARGV[1] = newOwnerGateId
-- ARGV[2] = newExpiresAtMs
-- ARGV[3] = ttlMs
-- ARGV[4] = nowMs
--
-- Returns: "EXPIRED" | "SAME" | "<prevOwnerGateId>"

local raw = redis.call('HGETALL', KEYS[1])
if (#raw == 0) then return "EXPIRED" end

local tbl = {}
for i = 1, #raw, 2 do tbl[raw[i]] = raw[i+1] end

local expiresAt = tonumber(tbl['expiresAt'] or '0')
if (expiresAt <= tonumber(ARGV[4])) then return "EXPIRED" end

local prev = tbl['ownerGateId']
redis.call('HSET', KEYS[1], 'ownerGateId', ARGV[1], 'expiresAt', ARGV[2])
redis.call('PEXPIRE', KEYS[1], ARGV[3])
redis.call('PEXPIRE', KEYS[2], ARGV[3])

if (prev == ARGV[1]) then return "SAME" end
return prev
```

幂等：同一新 owner 多次调用 → 后续返回 "SAME"，不再发 evict。

## 3. Pub/Sub 事件 schema

通道：`gate:flow:evict`。

```json
{
  "flowId": "5c3a1e6f-...-4f9a",
  "newOwnerGateId": "gate-02",
  "evictedAt": 1736612345678
}
```

订阅者侧 JSON 解析失败、字段缺失 → 跳过且 emit `gate_flow_cross_evict_invalid_total`。

## 4. FlowSessionManager 状态机变化

| 来源 | Phase A 行为 | B2 行为 |
|------|------------|--------|
| record.ownerGateId == self | RESUMED | RESUMED (`owner_same`) |
| record.ownerGateId != self & Redis ok | REJECTED_OWNER_OTHER | crossTakeover → `RESUMED owner_changed` + publish evict |
| record.ownerGateId != self & Redis 故障 | REJECTED_OWNER_OTHER | REJECTED_OWNER_OTHER（保留兜底） |
| record 过期 | REJECTED_EXPIRED | REJECTED_EXPIRED（Lua 返回 EXPIRED） |
| record 缺失 + local 也缺失 | REJECTED_EXPIRED | REJECTED_EXPIRED |

## 5. 与 B1 buffer 的关系

- B1 buffer 仅存内存：跨实例 RESUME 之后新 gate **没有任何历史 entry**。
- `replayPending(session, channel, fromSeq)` 在新 gate 上自然返回 `count=0`，但 metric 会记 `gate_flow_buffer_replay_total +0`，不影响功能。
- 客户端 `last_client_recv_seq` 仍写进 `FlowSession.lastSeqAnchor` 与 Redis `lastSeqAnchor`，作为「客户端最后已确认的 gwSeq」记账；B3 会用该锚点合并离线流。
- metric `gate_flow_cross_takeover_total{outcome=owner_changed}` 是「跨实例 RESUME 必然丢消息」的关键监控信号，运维需关注。

## 6. 失败 / 容错

| 场景 | 行为 |
|------|------|
| 新 gate crossTakeover 成功但 PUBLISH 失败 | 老 gate 不会驱逐 → 老 gate 仍持有「过时 ATTACHED」FlowSession；待心跳超时 / channel write 失败被动清理；同时 Redis owner 已改 → 老 gate 的 renew/destroy 受 owner guard 保护，不影响 byplayer 索引一致性 |
| 老 gate 收到 evict 但本地无该 flow | no-op，emit `gate_flow_cross_evict_unknown_total` |
| Redis Cluster 模式下 Pub/Sub | Redis Pub/Sub 跨节点广播；channel 名固定无 hash-tag 问题 |
| 网络抖动导致同一 evict 被两次 deliver | 幂等（listener 操作仅是「移除/关闭」） |
| 老 gate 进程崩溃后再启动 | 无影响；本地索引天然空，Redis 由新 gate 维护 |

## 7. 性能与容量

- 单次跨实例 RESUME 增加：1 次 Lua（< 1ms RTT）+ 1 次 PUBLISH（< 1ms）= 总 +~2ms。
- PUBLISH 频次约等于「跨实例 RESUME 次数」，常规规模 ≤ 100/s，对 Redis 影响可忽略。
- listener 线程：Spring `RedisMessageListenerContainer` 默认 SimpleAsyncTaskExecutor，单消息处理 O(1)，无背压风险。

## 8. 风险

- **客户端依旧可能感知短暂消息丢失**：跨实例 RESUME 期间 gate-A 已持有的未投递下行帧（gwSeq 在 buffer 中）随 evict 被丢弃。该问题本变更不解决，由 B3 设计 fallback。
- **Self-evict 误报**：若误配置导致老 gate 监听同一频道并收到自己发的 evict（newOwner == self），可能误删本地新 flow；listener 已做 `newOwnerGateId == self` 跳过，并提供 `publish-self-evict=false` 配置默认禁止主动发 self-evict。
- **`gate.id` 必须全局唯一**：依赖 `gateConfig.getId()` 区分实例；运维必须保证 K8s pod / 物理机的 gate id 不冲突。

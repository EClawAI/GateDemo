## 1. 概念分层

```text
                       ┌──────────────────────────────────────────────┐
                       │ FlowSession  ( logical , keyed by flowId )   │
                       │  · playerId, gameId, ownerGateId             │
                       │  · createdAt / detachedAt / expiresAt        │
                       │  · lastClientRecvSeq (anchor)                │
                       │  · currentChannelRef  ←─┐                    │
                       └──────────────────────────────────────────────┘
                                                 │ 1:1 当前绑定
                       ┌─────────────────────────▼────────────────────┐
                       │ NetunSession-like ( transport , per Channel )│
                       │  · Netty Channel + Attribute(PLAYER_ID,...)  │
                       └──────────────────────────────────────────────┘
```

- **FlowSession**：跨多次传输存活；**真相在 Redis**，gate 进程内保留一份 TTL 内的内存快照（性能与 detach/attach 路径需要）。
- **NetunSession**：本变更不新建专门类型，由现有 `ChannelHandlerContext` + Channel Attribute 承载；命名只在文档/日志中沿用，**避免**为命名而做的过度抽象。

## 2. 与 Gateway2 的差异（克制借用）

| 维度 | Gateway2 (icefire-game-gateway) | 本变更（gate-service） |
|------|---------------------------------|-------------------------|
| 多传输 | TCP/WS/KCP/QUIC 统一帧协议 | **仅 WebSocket**，不引入新传输 |
| USP 状态机 | Opening/Ready/Stable 等 | **不引入**；仍以现有 AUTH 完成视为 Stable |
| 下行缓冲 + seq | DownstreamBuffer + gwSeq | **不实现**（Phase B） |
| 跨实例 takeover | Redis Lua 原子 takeover | **不实现**（Phase B） |
| 上游 | 与 GS 长连 + Netun 帧 | **保持 gRPC**，路由键仍 `playerId` |
| flow 唯一 ID | 服务端 `UUID.randomUUID()` | **同样 UUID**（v4），文本形式 36 字节 |

> 借用：**双层 session 概念 + flowId + Detach/Attach 生命周期**；不借用协议帧、USP、跨实例迁移。

## 3. AUTH 双路径状态机

```text
              ┌─────────────────────────────────────────┐
              │  client sends AuthRequest               │
              │  ( token, gameId,                       │
              │    optional flowId,                     │
              │    optional last_client_recv_seq )      │
              └───────────────┬─────────────────────────┘
                              │
        flowId absent ?       │       flowId present ?
        ────────────────┐     │     ┌──────────────────
                        ▼     ▼     ▼
                ┌────────────┐ ┌────────────────────────┐
                │   NEW path │ │ RESUME path            │
                │            │ │                        │
                │ - validate │ │ - validate token       │
                │   token    │ │ - load flow from Redis │
                │ - mint     │ │ - check playerId match │
                │   flowId   │ │ - check expiresAt      │
                │ - replace  │ │ - check ownerGateId    │
                │   existing │ │                        │
                │   flow for │ │   ok ─► attach Channel │
                │   playerId │ │   fail ─► fall back to │
                │            │ │           NEW (close   │
                │            │ │           old flow,    │
                │            │ │           mint new)    │
                └─────┬──────┘ └─────────┬──────────────┘
                      │                  │
                      └────────┬─────────┘
                               ▼
                  AuthResponse { success, playerId,
                                 flowId, resumeStatus }
```

`resume_status` 取值：

- `NEW`（首次签发）
- `RESUMED`（成功换绑）
- `REJECTED_EXPIRED` / `REJECTED_MISMATCH` / `REJECTED_OWNER_OTHER` → **自动降级为 NEW**（同请求内继续走 NEW 路径并返回新 flowId；客户端可据 `resume_status` 决策是否重发未确认请求）。

## 4. FlowSession 生命周期

```text
       NEW AUTH              RESUME AUTH ok          Channel close
   ┌──────────────┐       ┌──────────────────┐     ┌────────────────┐
   │  CREATED     │──────▶│  ATTACHED        │────▶│  DETACHED       │
   │  (in Redis + │       │  (currentChannel │     │  (no channel,    │
   │   in mem)    │       │   active)        │     │   in mem + Redis │
   └──────────────┘       └───────▲──────────┘     │   until TTL)     │
                                  │                └──────┬───────────┘
                                  │  RESUME AUTH ok       │
                                  └───────────────────────┘
                                                          │
                                       超时 / 顶号 / 显式 logout
                                                          ▼
                                                  ┌───────────────┐
                                                  │  DESTROYED    │
                                                  │  (remove redis│
                                                  │   + mem)      │
                                                  └───────────────┘
```

- **CREATED → ATTACHED**：NEW AUTH 完成时立即 ATTACHED；中间无显式 CREATED 持久态。
- **ATTACHED → DETACHED**：`channelInactive` 时触发；置 `detachedAt = now`，刷 Redis。
- **DETACHED → DESTROYED**：以下任一触发即销毁：
  - 超过 `flow.detached-ttl-seconds`（默认 **60s**，可配）；
  - 同一 `playerId` 走 NEW 路径登录（顶号）；
  - 客户端发出显式登出（后续可加，不在本变更范围）。
- **总寿命**：以 `flow.max-ttl-seconds`（默认 **24h**）兜底，避免 Redis 漂泊。

## 5. Redis schema（最小集）

| Key | 类型 | 字段 / 内容 | TTL |
|------|------|--------------|------|
| `gate:flow:<flowId>` | Hash | `playerId`, `gameId`, `ownerGateId`, `createdAt`, `detachedAt`, `lastSeqAnchor`, `expiresAt` | 与 `expiresAt` 对齐 |
| `gate:flow:byplayer:<playerId>` | String | `flowId`（当前活跃） | 与对应 flow 同寿 |

- **顶号原子性（MVP）**：使用 Redis Lua 脚本一次性 `GET byplayer → DEL old flow hash → SET new byplayer → HMSET new flow`；脚本里限单玩家单 flow。
- **续期**：`ATTACHED` 状态下网关定期 `EXPIRE` 续期（与 Gateway2 renewal 一致思路）；`DETACHED` 不续期，依赖 TTL 兜底回收。

> **不引入** Redis Pub/Sub 或多 gate 实例的跨节点通知（Phase B 范围）。

## 6. gate-service 内部职责切分

| 类（建议名） | 职责 |
|-------------|------|
| `FlowSession`（POJO） | 内存中 flow 状态对象（`flowId`、`playerId`、`gameId`、`currentChannelRef`、`lastDetachedAt` 等）。 |
| `FlowSessionManager`（@Service） | `playerId ↔ flowId ↔ FlowSession` 索引；NEW / RESUME / Detach / Destroy 入口；与 `RedisFlowStore` 协同。 |
| `RedisFlowStore`（@Service） | Redis 读写封装（含 Lua 脚本与 TTL 续期）。 |
| `PlayerService` | **保留并降级**：对外保留 `sendToPlayer(playerId, message)` 等便捷方法，内部委托 `FlowSessionManager` 找 `currentChannel`。 |
| `GateNettyWebSocketHandler` | `handleAuth` 分叉到 NEW / RESUME；`channelInactive` 调 `FlowSessionManager.markDetached`。 |

> 命名以实现 PR 为准；本节给出语义边界，不强制 Java 类名。

## 7. 与现有 `gatedemo` spec 张力（处理方式）

工作区 `openspec/specs/gatedemo/spec.md` 的 **Requirement: 无状态网关设计** 当前措辞强调「会话数据存储于 Redis」。

- **不冲突解读**：本变更下 **集群级真相** 仍在 Redis；gate 进程内的 `FlowSession` 是 **TTL 内的缓存对象**，进程崩溃后由 Redis + RESUME 重建（Phase A 仅同实例 RESUME；Phase B 才跨实例）。
- **建议在归档后**对 `openspec/specs/gatedemo/spec.md` 做小修订：把「无状态」精确化为「**集群级状态在 Redis；gate 实例可持 TTL 内 flow 缓存**」。该修订 **不在本变更内执行**，避免跨工作区改动 noise。

## 8. 与离线消息 Streams 的边界

| 场景 | 走 flow 缓冲（Phase B 才有） | 走离线 Streams（现状） |
|------|------------------------------|--------------------------|
| 弱网瞬断、flow 未过 TTL、RESUME 成功 | ✔（Phase B） | ✘ |
| flow 已过 TTL / 被顶号 / DESTROYED | ✘ | ✔ |

Phase A **不实现**任何下行缓冲，因此 **现有离线 Streams 行为 100% 保留**；新增协议字段对老路径无影响。

## 9. 可观测性

- **结构化日志**：`event=flow.new` / `flow.resumed` / `flow.detached` / `flow.destroyed`，字段 `flowId, playerId, gameId, ownerGateId, ageMs, reason`。
- **Metrics**（建议命名）：
  - `gate_flow_total{event=new|resumed|destroyed,reason=...}` counter；
  - `gate_flow_active` gauge（当前 ATTACHED + DETACHED 之和）；
  - `gate_flow_attached` gauge；
  - `gate_flow_resume_latency_ms` histogram（RESUME 校验 → attach 完成）。
- **失败路径**：`resume_status != RESUMED` 时按 `reason` 打标签计数。

## 10. 决定与权衡

- **flowId = UUIDv4**：服务端 mint，36 字节字符串；不引入额外字典或 short-id 服务，简单可观察。
- **客户端持久化**：客户端在收到 `AuthResponse.flow_id` 后 **本地持久化**（player-client demo 用本地文件 / 内存）；下次 AUTH 自动带上。
- **`last_client_recv_seq` 在 Phase A 仅记录、不强制使用**：客户端可选携带，服务端做 anchor 持久化与日志，**不**用它驱动重放；Phase B 引入缓冲时直接消费。
- **拒绝隐式 RESUME**：客户端不带 `flowId` 即视为 NEW；不做「相同 token 即视作 RESUME」的隐式合并，避免歧义。

## 11. 风险

- **顶号 race**：同一玩家两端几乎同时 NEW 登录 → 依赖 Lua 脚本原子替换；测试用集成用例覆盖。
- **Redis 故障**：Redis 不可用时 `FlowSessionManager` 应 **降级**为「等价当前实现」的纯内存路径（即每次 AUTH 都按 NEW 走、不做跨连接 RESUME），并打告警；不得阻塞 AUTH。
- **TTL 数值不合适**：60s detach TTL 是初值；上线后据弱网用户的实际重连分布调整（监控 `gate_flow_resume_latency_ms` 与 RESUME 命中率）。
- **客户端老版本兼容**：旧客户端不带 `flow_id` 字段 → 永远走 NEW，行为与今日一致；新字段为 proto3 optional，向前兼容。

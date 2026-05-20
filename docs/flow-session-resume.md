# FlowSession & 弱网 RESUME 重连

本文对应 OpenSpec change `add-netun-resume-flow-session` 与 capability `gate-resume-reconnect`。
设计依据：`openspec/changes/add-netun-resume-flow-session/design.md` §1-§11。

## 1. 概念分层

| 层 | 标识 | 寿命 | 真相位置 |
|------|------|------|----------|
| **传输 NetunSession** | Netty `Channel` | 与 WebSocket 连接同生灭 | 内存 |
| **逻辑 FlowSession** | 服务端 mint 的 `flow_id`（UUIDv4） | 跨多次传输，受 TTL 约束 | **Redis**（gate 进程内保留 TTL 内缓存） |

- 同一 `playerId` 在集群范围 **至多一条** 未过期 FlowSession（顶号原子替换）。
- `ownerGateId` 在 Phase A 用于限定 RESUME 只能在原 gate 实例完成；跨实例 RESUME 返回 `REJECTED_OWNER_OTHER` 并降级 NEW。

## 2. 协议字段

`AuthRequest`（`proto/gate_protocol.proto`）：

| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `token` | string | 是 | JWT，决定 `playerId` |
| `game_id` | int32 | 是 | 绑定的 game 实例 |
| `flow_id` | string | 否 | RESUME 路径；缺省 / 空串 → NEW |
| `last_client_recv_seq` | uint64 | 否 | 客户端最后一次 ACK 的下行 seq；Phase A 仅记录 |

`AuthResponse`：

| 字段 | 含义 |
|------|------|
| `success` | 鉴权是否通过 |
| `player_id` | 解析出的玩家 ID |
| `flow_id` | 服务端最终持有的 flow id（NEW / RESUMED 都会返回） |
| `resume_status` | 见下表 |

`ResumeStatus` 枚举：

| 值 | 含义 | 客户端策略建议 |
|----|------|----------------|
| `NEW` | 服务端首次 mint flow id（或客户端未带 flow id） | 保存 `flow_id`；按全新会话恢复 UI |
| `RESUMED` | 同 flow id 在同实例成功换绑 Channel | 复用本地未确认请求队列，无需重发 |
| `REJECTED_EXPIRED` | flow 已过 `expiresAt`，已降级 NEW | 视为新会话；丢弃旧未确认请求或回放至重连提示 |
| `REJECTED_MISMATCH` | `playerId` / token 不一致 | 视为新会话；提示用户重登录 |
| `REJECTED_OWNER_OTHER` | flow 在另一 gate 实例 owner，已降级 NEW | 视为新会话；可选择上报埋点观察跨实例切换频率 |

## 3. Redis Schema（详见 `docs/redis-key-design.md`）

| Key | 类型 | TTL | 内容 |
|------|------|-----|------|
| `gate:flow:<flowId>` | Hash | 与 `expiresAt` 对齐（默认 60s） | `playerId`, `gameId`, `ownerGateId`, `createdAt`, `expiresAt`, `detachedAt`, `lastSeqAnchor` |
| `gate:flow:byplayer:<playerId>` | String | 与上同寿 | 当前活跃 `flowId` |

「单玩家单 flow」原子顶替由 `gate-service/src/main/resources/lua/flow_takeover.lua` 完成。

## 4. TTL 与配置项

| 配置 | 默认 | 说明 | 环境变量 |
|------|------|------|----------|
| `gate.flow.detached-ttl-seconds` | 60 | DETACHED 后允许 RESUME 的最大秒数 | `GATE_FLOW_DETACHED_TTL_SECONDS` |
| `gate.flow.max-ttl-seconds` | 86400 | flow 自创建起最大总寿命 | `GATE_FLOW_MAX_TTL_SECONDS` |
| `gate.flow.renewal-interval-seconds` | 15 | ATTACHED 状态下网关续期 Redis 的间隔 | `GATE_FLOW_RENEWAL_INTERVAL_SECONDS` |
| `gate.flow.detached-scan-interval-seconds` | 5 | 内存中 DETACHED 超时扫描间隔 | `GATE_FLOW_DETACHED_SCAN_INTERVAL_SECONDS` |
| `gate.flow.redis-key-prefix` | `gate:flow:` | Redis key 前缀，便于多实例 / 测试隔离 | `GATE_FLOW_REDIS_KEY_PREFIX` |

调优指南：

- 弱网用户聚集（如移动端切网、地铁场景）建议把 `detached-ttl-seconds` 调到 90-120s；
- 资源紧张时把 `max-ttl-seconds` 调短（如 6h）防止 Redis 漂泊；
- `renewal-interval-seconds` 不应超过 `detached-ttl-seconds / 3`，否则 ATTACHED flow 可能因没及时续期被误判 DETACHED。

## 5. 可观测性

### 结构化日志

logger 名：`gate.flow.event`（可在 logback 单独路由）。每条事件包含：

```
event=<flow.new|flow.resumed|flow.detached|flow.destroyed>
flowId=<uuid> playerId=<long> gameId=<int> ownerGateId=<gate-id> [extra=key=val,...]
```

### Metrics

| Meter | 类型 | 说明 |
|-------|------|------|
| `gate_flow_total{event,reason}` | Counter | flow 生命周期事件累计；`event` ∈ `new` / `resumed` / `detached` / `destroyed` / `resume_rejected`；`reason` 携带 `ok` / `redis_unavailable` / `resume_rejected_expired` / `expired` / `mismatch` / `owner_other` / `channel_inactive` / `detached_ttl` / `max_ttl` 等。**注**：`event=cross_takeover` 自 `add-flow-observability-buckets` 起停止 emit，已迁移到 `gate_flow_takeover_total{result}`。 |
| `gate_flow_active` | Gauge | 当前 FlowSession 总数（ATTACHED + DETACHED） |
| `gate_flow_attached` | Gauge | 当前 ATTACHED 的 FlowSession 数 |
| ~~`gate_flow_resume_latency_ms`~~ | ~~Timer~~ | **已下线**（`add-flow-observability-buckets`）→ `gate_flow_latency_resume_ms{kind=same_gw\|cross_gw\|new}` |

> **Phase B observability 升级**：完整 metric 字典、PromQL 示例、配置开关详见 [`observability-flow-metrics.md`](observability-flow-metrics.md)。本节仅保留 Phase A 的基础 lifecycle metric 视角，不再覆盖跨实例 / RESUME 分桶细节。

均通过现有 Prometheus 端点 `/actuator/prometheus` 暴露。

### 调试端点

`GET /debug/flows`（仅在 `dev` profile 开启）返回当前实例所有 flow 的快照。

## 6. 客户端集成指南

参考 `player-client/src/main/java/com/clawai/gatedemo/client/flow/FlowSessionStore.java`。

1. **保存**：每次收到 `AuthResponse.success=true` 后，把 `flow_id` 持久化到本地（demo 用 `${user.home}/.gatedemo/flow-session.txt`，真实客户端建议使用 PlayerPrefs / KeyChain / SecureStorage）。
2. **携带**：每次重连发送 `AuthRequest` 时，若本地存在已保存的 `flow_id` 与 `last_client_recv_seq`，附在请求中。
3. **解释 `resume_status`**：
   - `RESUMED` 时可复用本地状态；
   - 任何 `REJECTED_*` 都意味着服务端已降级 NEW，客户端需要丢弃旧未确认请求或重发。
4. **可配置 TTL 感知**：若客户端能预判离线时长会显著超过服务端 `detached-ttl-seconds`，可选择直接清掉本地 `flow_id`，避免无意义的 RESUME → REJECTED_EXPIRED → NEW 往返。

## 7. 弱网演示步骤

> 前提：已按 `docs/api-versioning-strategy.md` 编译 proto；按 `application.yml` 启动 gate-service / login-service / game-service。

1. `cd player-client && mvn spring-boot:run`（首次启动会写出 `~/.gatedemo/flow-session.txt`）。
2. 观察日志：第一次 AUTH 应记录 `status=NEW, flowId=<uuid>`。
3. 用 `kill -STOP` 暂停 player-client，模拟移动端切网；或 `pkill -f player-client` 直接关停。
4. **60 秒以内** 重启 player-client，应在日志看到 `status=RESUMED, flowId=<相同 uuid>`，gate-service 输出 `event=flow.resumed`。
5. 超过 60 秒后再重启，应看到 `status=REJECTED_EXPIRED, flowId=<新 uuid>` 与 `event=flow.new reason=resume_rejected_expired`。

## 8. Phase 边界与未来计划

Phase A（本变更）**不实现**：

- 跨 gate 实例的 owner 迁移（出现即 `REJECTED_OWNER_OTHER`）；
- 下行 seq + 缓冲重放（`last_client_recv_seq` 仅记录）；
- 多传输（KCP/QUIC 等）。

后续 change 引入这些能力时，本文档将随之更新；OpenSpec 工作流确保 `specs/gate-resume-reconnect/spec.md` 始终与实现一致。

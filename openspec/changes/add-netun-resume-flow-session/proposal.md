## Why

`gate-service` 当前把 **`playerId → Channel`** 直接做一一映射（`PlayerService` `ConcurrentMap<Long, Channel>`），传输断 = 在线状态消失；弱网/切网/熄屏导致的瞬断只能走 **全量重登录 + 离线消息回放**，体验差，且无法区分「**重连**」与「**顶号/新设备登录**」。

本变更引入参考 Gateway2 (icefire-game-gateway) 的 **`FlowSession` / `NetunSession` 两层 session 模型** 的最小切片：以 **服务端在首次 AUTH 成功后下发的独立 `flowId`** 作为「**这条客户端 ↔ 网关逻辑会话**」的稳定身份，**RESUME** 时复用同一 `flowId`、**换绑新 WebSocket Channel**，为后续 **下行缓冲 / 跨实例接管 / 多传输** 预留位。

## What Changes

### 概念分层

- **NetunSession（传输层）**：与单个 Netty `Channel` 1:1，挂为 Channel Attribute；连接关闭即消失。
- **FlowSession（逻辑层，本变更引入）**：以独立 `flowId` 为键，承载 `playerId`、`gameId`、`ownerGateId`、Detach 时间戳、`lastClientRecvSeq` 锚点等；**可跨多次传输存活**（受 TTL 约束）。

### 握手两条路径（协议层显式区分）

- **NEW**：客户端不带 `flowId`（或带的 `flowId` 已失效）。AUTH 成功后服务端 **mint** 新 `flowId` 并通过 `AuthResponse` 下发；**原子失效** 该 `playerId` 既有活跃 flow（等价当前「顶号」语义，但通过 flow 显式表达）。
- **RESUME**：客户端带 **既有 `flowId`** + `last_client_recv_seq`。网关校验（存在、未过期、`playerId` / token 一致、owner 可接管）后 **attach 新 Channel** 到既有 FlowSession，**不重新 mint flowId**。

### 单玩家单活跃 flow（MVP）

同一 `playerId` 在集群范围 **至多一条** 未过期 flow；NEW 登录 **原子替换** 旧 flow 记录。多设备/多 Tab 在本变更不开放，留到后续 change 显式规格化。

### 集群真相：Redis

flow 记录至少包含：`flowId`、`playerId`、`gameId`、`ownerGateId`、`createdAt`、`detachedAt`、`lastSeqAnchor`、`expiresAt`；写入 / 续期 / 删除均由网关侧封装；TTL 与续期周期在 `design.md` 数值化。

### gate-service 内部对象（短生命周期）

`PlayerService` 的语义从 `playerId → Channel` 扩展为 `playerId → FlowHandle{ flowId, currentChannel, lastDetachedAt, ... }`；**顶号** = 关旧 Channel + invalidate 旧 flow + 注册新 flow；**RESUME** = 换绑 `currentChannel`，不重建 `FlowHandle`。

### game-service / gRPC 路由

**不在本变更动**：仍以 `playerId` 为主键路由 gRPC stream。`flowId` 暂仅在 gate↔Redis↔client 三角内使用，后续如需在 game 侧做审计/串行可作为 metadata 透传（另立 change）。

### 协议形状

- `AuthRequest` 新增可选 `flow_id` 与 `last_client_recv_seq`（缺省视为 NEW）。
- `AuthResponse` 新增 `flow_id` 与 `resume_status`（`NEW` / `RESUMED` / `REJECTED_*`）。
- 不引入新消息号；以兼容字段扩展 `gate_protocol.proto`（向前兼容旧客户端）。

### Phase 边界（MVP = Phase A）

- **Phase A（本变更交付范围）**：NEW 下发 `flowId`、Redis 记录、**同一 gate 实例内** detach/attach、RESUME 换绑 Channel、单玩家单 flow 约束、可观测性日志。
- **Phase B（后续 change）**：跨 gate 实例 owner 迁移（Lua 原子 takeover）、下行 seq + 缓冲重放、与离线 Streams 的合流策略。本变更 **不实现**，但在设计上 **不阻断**。

## Capabilities

### New Capabilities

- `gate-resume-reconnect`：网关层引入独立 `flowId` 的双层会话模型；定义 NEW / RESUME 握手语义、Redis flow 真相源、单实例内 detach/attach、单玩家单 flow 约束、可观测性最低要求。

### Modified Capabilities

（无需求层级变更；与现有 `capability-jwt-auth`、`capability-websocket-gateway`、`capability-offline-message`、`capability-resilience` 在实现层 **互不冲突**，相关说明见 `design.md`。）

## Impact

- **代码**：`gate-service` 下 `service/PlayerService`、`handler/GateNettyWebSocketHandler`（AUTH 路径分叉）；新增 `FlowSession` / `FlowSessionManager` / `RedisFlowStore` 等类（命名以 `design.md` 为准）。
- **协议**：`proto/gate_protocol.proto` 中 `AuthRequest` / `AuthResponse` 兼容扩展；客户端 SDK 需配合保存 / 携带 `flowId`（player-client demo 一并更新）。
- **依赖**：无新 Maven 依赖；复用现有 Redis（Lettuce / Jedis 视当前 stack）。
- **运维**：新增 Redis key 前缀（如 `gate:flow:<flowId>`、`gate:flow:byplayer:<playerId>`），需在容量与清理策略说明里登记。
- **跨规格**：与工作区 `openspec/specs/gatedemo/spec.md`「无状态网关」表述存在张力——`design.md` 中说明「**对外**仍按 Redis 为真相、**对内**允许 TTL 内 flow 对象」并建议归档后微调主规格。

## Non-Goals

- **不实现** Phase B（跨实例 takeover / 下行 seq + 缓冲重放 / 多传输）。
- **不修改** `game-service` 的 gRPC stream 路由键（仍 `playerId`）。
- **不引入** 新消息号 / 新连接握手帧（与 Gateway2 的 USP 协议 **不**绑定，仅借用其会话分层概念）。
- **不放开** 单玩家多 flow / 多 Tab 并行在线。

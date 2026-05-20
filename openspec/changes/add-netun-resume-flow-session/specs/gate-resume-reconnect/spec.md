# gate-resume-reconnect — Delta

## ADDED Requirements

### Requirement: 双层 Session 模型与独立 `flowId`

`gate-service` SHALL 区分 **传输层 session**（与单个 Netty `Channel` 1:1，随连接生灭）与 **逻辑层 FlowSession**（以服务端 mint 的独立 `flowId` 为键，可跨多次传输连接存活，受 TTL 约束）。`flowId` SHALL 由服务端在 **NEW** 路径的 AUTH 成功时生成（UUID v4），SHALL NOT 由客户端自定义。

#### Scenario: 首次 AUTH 下发 flowId

- **GIVEN** 客户端首次连接 `gate-service` 并发送不含 `flow_id` 的 `AuthRequest`
- **WHEN** Token 与 `gameId` 校验通过
- **THEN** 服务端 SHALL mint 新 `flowId` 并在 `AuthResponse` 中返回
- **AND** `resume_status` SHALL 为 `NEW`
- **AND** Redis 中 SHALL 写入对应 flow 记录（含 `playerId`, `gameId`, `ownerGateId`, `createdAt`, `expiresAt`）

#### Scenario: 客户端伪造 flowId 无效

- **GIVEN** 客户端在 `AuthRequest.flow_id` 中提供未在 Redis 注册的字符串
- **WHEN** 服务端处理 RESUME 路径
- **THEN** SHALL 视为 RESUME 失败并降级为 NEW
- **AND** `AuthResponse.resume_status` SHALL 为 `REJECTED_*` 系列之一（具体取决于失败原因），随后 `flowId` 仍为新 mint 值

### Requirement: AUTH 双路径（NEW 与 RESUME）

`AuthRequest` SHALL 支持可选字段 `flow_id` 与 `last_client_recv_seq`。`gate-service` SHALL 据此分叉为 **NEW** 与 **RESUME** 两条处理路径。RESUME 校验失败 SHALL 自动降级为 NEW，并在同一 `AuthResponse` 中返回新 `flowId` 与对应 `resume_status`。

#### Scenario: RESUME 成功换绑 Channel

- **GIVEN** 客户端持有上次会话下发的 `flowId`，对应 flow 在 Redis 中存在、未过期、`playerId` 与 Token 解析结果一致、`ownerGateId` 为本实例
- **WHEN** 客户端在新 WebSocket 连接上发送 `AuthRequest{flow_id, last_client_recv_seq}`
- **THEN** 服务端 SHALL 将该 FlowSession 的 `currentChannel` 换绑为新 `Channel`
- **AND** 既有 `flowId` SHALL 不变
- **AND** `AuthResponse.resume_status` SHALL 为 `RESUMED`

#### Scenario: RESUME 因过期降级为 NEW

- **GIVEN** 客户端持有的 `flowId` 对应 flow 在 Redis 中已超过 `expiresAt`
- **WHEN** 客户端发送 `AuthRequest{flow_id}`
- **THEN** 服务端 SHALL 在同一请求处理内 mint 新 `flowId` 并替换 Redis 记录
- **AND** `AuthResponse.resume_status` SHALL 为 `REJECTED_EXPIRED`
- **AND** `AuthResponse.flow_id` SHALL 为新 mint 的值

#### Scenario: RESUME 因 playerId 不匹配被拒

- **GIVEN** `flowId` 对应 flow 在 Redis 中存在但 `playerId` 与本次 Token 解析结果不一致
- **WHEN** RESUME 校验
- **THEN** 服务端 SHALL 拒绝复用该 flow，`resume_status` SHALL 为 `REJECTED_MISMATCH`
- **AND** SHALL 走 NEW 路径并返回新 `flowId`

### Requirement: 单玩家单活跃 Flow（顶号原子语义）

同一 `playerId` 在集群范围 SHALL 至多存在一条未过期 FlowSession（含 ATTACHED 与 DETACHED）。NEW 路径 AUTH SHALL 原子地替换该 `playerId` 现有 flow（关闭旧 `Channel`，删除旧 Redis 记录，注册新记录）。

#### Scenario: 顶号关闭旧连接

- **GIVEN** 玩家 A 在 gate-01 上有 ATTACHED FlowSession（`flowId=F1`）
- **WHEN** 玩家 A 在另一端发起 NEW 登录（不带 `flowId`）
- **THEN** 旧 `Channel` SHALL 被关闭
- **AND** Redis 中 `F1` 相关 Hash 与 `gate:flow:byplayer:<playerId>` SHALL 被原子替换为新 flow `F2`
- **AND** 旧 `Channel` 对应 FlowSession 内存对象 SHALL 被销毁

#### Scenario: 并发 NEW 登录不产生双活 flow

- **GIVEN** 两个 NEW `AuthRequest` 几乎同时到达不同 gate 实例
- **WHEN** 二者均通过 Token 校验
- **THEN** Redis 中最终 SHALL 仅保留一条 `gate:flow:byplayer:<playerId>` 与对应 flow Hash
- **AND** 另一条 SHALL 被原子失效（败者的 `Channel` 被服务端关闭或不被 ATTACH）

### Requirement: FlowSession 生命周期与 TTL

FlowSession SHALL 经历 `ATTACHED → DETACHED → DESTROYED` 状态转换。`channelInactive` SHALL 触发 `ATTACHED → DETACHED`，并记录 `detachedAt`。DETACHED 状态在配置的 `flow.detached-ttl-seconds`（默认 60 秒）后 SHALL 转入 `DESTROYED` 并清理 Redis 与内存对象。FlowSession 总寿命 SHALL 受 `flow.max-ttl-seconds`（默认 24 小时）兜底。

#### Scenario: 断线 60 秒内 RESUME 成功

- **GIVEN** FlowSession 处于 DETACHED 且 `now - detachedAt < 60s`
- **WHEN** 客户端携带相同 `flowId` 重连并通过 RESUME 校验
- **THEN** SHALL 转回 ATTACHED；`detachedAt` SHALL 清零；`expiresAt` SHALL 续期

#### Scenario: 断线超 TTL 自动销毁

- **GIVEN** FlowSession 处于 DETACHED 且未在 `flow.detached-ttl-seconds` 内被 RESUME
- **WHEN** 到达过期点
- **THEN** Redis 中对应 Hash 与 `byplayer` 索引 SHALL 被删除
- **AND** 内存对象 SHALL 被释放
- **AND** 后续相同 `flowId` 的 RESUME 请求 SHALL 被拒（`REJECTED_EXPIRED`），并降级为 NEW

### Requirement: Redis 为集群级真相、gate 为 TTL 缓存

FlowSession 的 **集群级真相** SHALL 存储于 Redis（至少键：`gate:flow:<flowId>` Hash 与 `gate:flow:byplayer:<playerId>` 索引）。`gate-service` 进程内 SHALL 仅保留 TTL 内的内存快照，进程崩溃后 SHALL 不阻碍后续以 RESUME（同实例或未来跨实例）恢复。

#### Scenario: 进程崩溃后内存快照重建

- **GIVEN** gate 实例崩溃重启
- **WHEN** 同一实例继续接收来自之前客户端的 RESUME 请求（`ownerGateId` 仍是本实例）
- **THEN** 服务端 SHALL 从 Redis Hash 读出 `playerId`、`gameId` 等信息重建内存对象并 ATTACH 新 `Channel`
- **AND** `resume_status` SHALL 为 `RESUMED`

#### Scenario: Redis 不可用时降级

- **GIVEN** Redis 不可用
- **WHEN** 客户端发起 AUTH（无论是否带 `flow_id`）
- **THEN** 服务端 SHALL 降级按 NEW 路径处理，不阻塞认证
- **AND** SHALL 打印告警日志，metrics 记录 `gate_flow_total{event=new,reason=redis_unavailable}`

### Requirement: 上游 gRPC 路由不变

`gate-service` 与 `game-service` 之间的 gRPC bidi stream 路由 SHALL 仍以 `playerId` 为主键，`flowId` SHALL NOT 进入现有 gRPC 路由决策。`flow_id` 可作为日志 / metadata 出现，但不构成路由键的一部分。

#### Scenario: RESUME 后上游 stream 不重建

- **GIVEN** 玩家 A 已通过 RESUME 在同一 gate 实例换绑新 `Channel`
- **WHEN** 客户端在新 `Channel` 上发送业务消息
- **THEN** gate SHALL 复用已有的 `playerId → gRPC stream` 路由，不主动断开或重建 stream
- **AND** game-service SHALL 不感知此次传输换绑

### Requirement: 协议字段向前兼容

`gate_protocol.proto` 中 `AuthRequest` SHALL 新增可选字段 `flow_id`（string）与 `last_client_recv_seq`（uint32 或更宽类型，由实现确定）。`AuthResponse` SHALL 新增 `flow_id`（string）与 `resume_status`（enum 或 string，取值见 design.md）。老客户端不携带新字段时 SHALL 等价于 NEW 路径。

#### Scenario: 老客户端兼容

- **GIVEN** 老版本客户端发送的 `AuthRequest` 不含 `flow_id`
- **WHEN** 服务端解析
- **THEN** SHALL 走 NEW 路径
- **AND** SHALL 返回 `AuthResponse.flow_id`；老客户端可忽略未知字段（proto3 默认行为）

### Requirement: 可观测性最低要求

`gate-service` SHALL 暴露与 flow 生命周期相关的结构化日志与 metrics：

- 结构化日志事件 SHALL 至少包含 `flow.new` / `flow.resumed` / `flow.detached` / `flow.destroyed`，字段包含 `flowId`、`playerId`、`gameId`、`reason`（如适用）；
- metrics SHALL 至少包含 `gate_flow_total{event,reason}` counter、`gate_flow_active` gauge、`gate_flow_attached` gauge、`gate_flow_resume_latency_ms` histogram。

#### Scenario: RESUME 失败带原因标签

- **GIVEN** 客户端 RESUME 因 `expiresAt` 过期失败
- **WHEN** 服务端处理完该请求
- **THEN** SHALL 输出 `event=flow.new` 日志（因降级到 NEW），同时
- **AND** SHALL 增加 `gate_flow_total{event=new,reason=resume_rejected_expired}` 计数

### Requirement: Phase 边界（不引入下行缓冲与跨实例 takeover）

本能力 SHALL NOT 在本变更内实现：(a) 下行消息 seq + 缓冲重放；(b) 跨 gate 实例的 Redis Lua takeover 与 owner 迁移；(c) 多传输（KCP/QUIC 等）。`ownerGateId` 字段 SHALL 写入 Redis，但跨实例 RESUME 在本变更内 SHALL 直接拒绝（`REJECTED_OWNER_OTHER`）并降级为 NEW。

#### Scenario: 跨实例 RESUME 被拒并降级

- **GIVEN** 客户端持有的 `flowId` 在 Redis 中 `ownerGateId` 为 `gate-01`
- **WHEN** 客户端连接到 `gate-02` 并发起 RESUME
- **THEN** `gate-02` SHALL 拒绝 RESUME，`resume_status` SHALL 为 `REJECTED_OWNER_OTHER`
- **AND** 同请求 SHALL 走 NEW 路径并返回新 `flowId`，旧 flow 被替换

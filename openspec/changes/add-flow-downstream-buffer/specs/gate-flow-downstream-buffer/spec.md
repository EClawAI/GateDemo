# gate-flow-downstream-buffer — Delta

## ADDED Requirements

### Requirement: 下行帧 `gwSeq` 标记与可选 8 字节扩展头

`gate-service` SHALL 为每条下行帧（PUSH / RESPONSE）按 `FlowSession` 维度单调递增地 stamp 一个 64-bit `gwSeq`。当对端客户端在 AUTH 阶段声明 `client_features.supports_gw_seq` 时，下行帧 SHALL 在 `MessageHeader.flags` 上置位 `FLAG_HAS_GW_SEQ = 0x2000`，并在 16 字节头之后、`bodyLength` 字节 body 之前插入 8 字节 big-endian unsigned `gwSeq`。

未声明 `supports_gw_seq` 的客户端 SHALL NOT 收到带该 flag 的帧；其下行帧布局与 Phase A 完全一致。

#### Scenario: 老客户端不受 gwSeq 影响

- **GIVEN** 客户端 AUTH 时 `client_features = 0`
- **WHEN** 服务端发送任意下行帧
- **THEN** 帧 SHALL 不置 `FLAG_HAS_GW_SEQ`
- **AND** 帧长度 SHALL 为 `16 + bodyLength`
- **AND** 客户端 SHALL 按 Phase A 解码路径正常处理

#### Scenario: 新客户端收到 gwSeq

- **GIVEN** 客户端 AUTH 时 `client_features.supports_gw_seq = 1`
- **AND** 服务端 `gate.flow.features.advertise-gw-seq = true`
- **WHEN** 服务端推送任意 PUSH / RESPONSE 帧
- **THEN** 帧 SHALL 置 `FLAG_HAS_GW_SEQ`
- **AND** 帧长度 SHALL 为 `16 + 8 + bodyLength`
- **AND** `gwSeq` SHALL 严格单调递增（同一 FlowSession 内）

### Requirement: per-FlowSession DownstreamBuffer

每个启用 buffer 的 `FlowSession` SHALL 持有一个 bounded ring buffer，保留 **已 stamp gwSeq 但尚未被客户端 ACK** 的下行帧字节副本。buffer 上限由 `gate.flow.buffer.capacity-entries`（默认 256）与 `gate.flow.buffer.capacity-bytes`（默认 4 MiB）共同决定，二者任一触达即触发 overflow 策略。

#### Scenario: ACK 触发裁剪

- **GIVEN** `FlowSession` 的 buffer 中保存 gwSeq=1..100 的 100 条 entry
- **WHEN** 客户端通过心跳上报 `last_client_recv_seq = 70`
- **THEN** buffer SHALL 仅保留 gwSeq ∈ (70, 100] 的 30 条 entry
- **AND** `FlowSession.lastSeqAnchor` SHALL ≥ 70
- **AND** Redis 中 `gate:flow:<flowId>` Hash 的 `lastSeqAnchor` 字段 SHALL 被异步更新

#### Scenario: overflow drop_oldest 策略

- **GIVEN** `gate.flow.buffer.overflow-policy = drop_oldest`
- **AND** buffer 已达 `capacity-entries` 上限
- **WHEN** 新一条下行帧入 buffer
- **THEN** 最老的 entry SHALL 被丢弃
- **AND** metric `gate_flow_buffer_dropped_total{policy=drop_oldest}` SHALL +1
- **AND** 结构化日志 SHALL 输出 `event=flow.buffer.overflow reason=drop_oldest droppedSeq=<seq>`

#### Scenario: overflow force_detach 策略

- **GIVEN** `gate.flow.buffer.overflow-policy = force_detach`
- **AND** buffer 已达 `capacity-bytes` 上限
- **WHEN** 新一条下行帧入 buffer
- **THEN** 当前 ATTACHED 的 Channel SHALL 被服务端主动 close
- **AND** `FlowSession` SHALL 进入 DETACHED 状态
- **AND** metric `gate_flow_buffer_overflow_total{policy=force_detach}` SHALL +1

### Requirement: RESUME 成功后从 buffer 重放

当 `FlowSessionManager.resume(...)` 返回 `RESUMED` 且会话满足 `client_features.supports_resume_replay = 1 AND server_features.supports_resume_replay = 1`，gate SHALL 在向客户端写回 `AuthResponse` **之前**，按 gwSeq 升序把 buffer 中 (`last_client_recv_seq`, 当前最大 gwSeq] 之间的所有 entry 重新 write 到新 Channel；写完 flush。

#### Scenario: 完整重放

- **GIVEN** `FlowSession` buffer 含 gwSeq 1..50
- **AND** 客户端在 AuthRequest 携带 `last_client_recv_seq = 30`
- **WHEN** RESUME 校验通过
- **THEN** 服务端 SHALL 按 gwSeq 升序补发 31..50 共 20 条帧
- **AND** 之后 SHALL 写出 `AuthResponse{resume_status=RESUMED, server_features.supports_resume_replay=1}`
- **AND** metric `gate_flow_buffer_replay_total` SHALL +20
- **AND** 结构化日志 SHALL 输出 `event=flow.buffer.replay fromSeq=30 toSeq=50 count=20`

#### Scenario: 客户端已最新

- **GIVEN** `FlowSession` buffer 含 gwSeq 1..50
- **AND** 客户端携带 `last_client_recv_seq = 50`
- **WHEN** RESUME 校验通过
- **THEN** 服务端 SHALL NOT 重放任何 entry
- **AND** metric `gate_flow_buffer_replay_total{count=0}` SHALL 不计数（直接跳过）

#### Scenario: 部分丢失（gap）

- **GIVEN** 之前发生 `drop_oldest`，buffer 中最小 gwSeq=20
- **AND** 客户端携带 `last_client_recv_seq = 5`
- **WHEN** RESUME 校验通过
- **THEN** 服务端 SHALL 重放 gwSeq ∈ [20, latest] 之间的 entry（已无 6..19）
- **AND** AuthResponse SHALL 额外置 `server_features` 中 bit 2 = `replay_had_gap`（建议保留位，本变更不强制定义客户端处理逻辑）

### Requirement: 客户端 ACK 通过心跳捎带

`ClientHeartbeat` proto 消息 SHALL 新增可选字段 `last_client_recv_seq`（uint64）。客户端 SHALL 在每次心跳中携带当前已成功处理的最大 gwSeq。`gate-service` 收到心跳后 SHALL 调用 `FlowSessionManager.ackSeq(playerId, seq)` 触发 buffer 裁剪与 `lastSeqAnchor` 更新。

#### Scenario: 老客户端 ACK 缺省

- **GIVEN** 客户端未声明 `supports_gw_seq`
- **WHEN** 发送心跳（`last_client_recv_seq` proto3 默认为 0）
- **THEN** 服务端 SHALL 把 0 视为「无 ACK」并不触发裁剪
- **AND** buffer 保持原状直到 DETACHED TTL 销毁

### Requirement: AUTH 阶段双向 features 协商

`AuthRequest` SHALL 新增 `client_features`（uint32 bitmask），`AuthResponse` SHALL 新增 `server_features`（uint32 bitmask）。bit 定义至少包含：

- `0x1` — `supports_gw_seq`
- `0x2` — `supports_resume_replay`

服务端 SHALL 取 `client_features & server_advertised_features` 写入 `AuthResponse.server_features`。会话期间所有「是否启用 gwSeq / 重放」决策 SHALL 以 `server_features` 为准。

#### Scenario: 协商关闭

- **GIVEN** 服务端 `gate.flow.features.advertise-gw-seq = false`
- **AND** 客户端声明 `client_features = 0x3`
- **WHEN** AuthResponse 返回
- **THEN** `server_features` SHALL = `0x2`（只保留 replay，没有 gwSeq 也无法 replay → 实际行为退化为不重放）
- **AND** 服务端实际行为：不 stamp gwSeq，不入 buffer，不重放

### Requirement: buffer 可观测性

`gate-service` SHALL 暴露以下 metrics：

- `gate_flow_buffer_size{playerId}` — gauge（仅在 dev profile 详细维度；生产可聚合为分桶 histogram）；
- `gate_flow_buffer_replay_total` — counter（累计重放帧数）；
- `gate_flow_buffer_overflow_total{policy}` — counter；
- `gate_flow_buffer_dropped_total{policy}` — counter；
- `gate_flow_buffer_ack_lag_ms` — histogram（最老未 ACK entry 的年龄）。

结构化日志事件 SHALL 至少包含 `flow.buffer.replay` 与 `flow.buffer.overflow`，含 `flowId, playerId, fromSeq, toSeq, count, reason` 字段。

#### Scenario: 重放计数器

- **GIVEN** 一次 RESUME 重放成功补发 20 条帧
- **WHEN** 重放完成
- **THEN** Prometheus 端点 `/actuator/prometheus` 暴露的 `gate_flow_buffer_replay_total` 指标值 SHALL +20
- **AND** 同请求 `event=flow.buffer.replay` 日志 SHALL 至少包含 `flowId`、`playerId`、`fromSeq`、`toSeq`、`count` 五个字段

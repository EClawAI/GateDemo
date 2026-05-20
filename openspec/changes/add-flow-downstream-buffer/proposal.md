## Why

Phase A（`add-netun-resume-flow-session`）已经把客户端 ↔ 网关之间的「逻辑会话」抽出为 `FlowSession` + `flow_id`，并允许 60s 内换绑新 Channel。但 **RESUME 成功后**，那段断网期间 game-service 推给玩家的下行消息，目前 **要么走离线 Streams 全量回放（昂贵 / 可能乱序），要么直接丢**。客户端只能 RESUMED 之后被动等离线重放，没有「真正无感」的体验。

参考 Gateway2 的 DownstreamBuffer + `gwSeq` 设计，本变更引入：

- **`gwSeq`**：网关在每条下行帧出网关前打的单调递增 64 位序号；
- **DownstreamBuffer**：挂在 `FlowSession` 上的 bounded ring buffer，保留未被客户端 ACK 的下行帧；
- **客户端 ACK**：心跳里捎带 `last_client_recv_seq`，网关据此裁剪 buffer；
- **RESUME 重放**：RESUME 成功后，网关先按 `last_client_recv_seq` 从 buffer 中按序补发未确认帧，再恢复正常流量。

为保持 **向前兼容**，下行帧格式扩展使用 `MessageHeader.FLAG_HAS_GW_SEQ` 标志位 + 客户端在 AUTH 阶段通过 `client_features` 显式声明对 `gwSeq` 的支持；老客户端零侵入。

## What Changes

### 协议层（兼容扩展）

- `MessageHeader` 新增 `FLAG_HAS_GW_SEQ = 0x2000`；置位时帧体在 16 字节头后多 8 字节 `gwSeq`（big-endian, unsigned 64-bit）。
- `AuthRequest` 新增 `client_features`（uint32 bitmask）：`bit 0 = supports_gw_seq`, `bit 1 = supports_resume_replay`。
- `AuthResponse` 新增 `server_features`（uint32 bitmask）反馈实际启用项；客户端按 AND 结果工作。
- `ClientHeartbeat` 新增可选 `last_client_recv_seq`（uint64）；缺省视为 0（首次心跳）。

### gate-service 内部

- `FlowSession` 新增 `nextGwSeq`（AtomicLong）、`DownstreamBuffer`（ring buffer，默认 256 entries / 4 MiB），由 `FlowSessionManager` 统一访问；
- `PlayerService.sendToPlayer` / `GameServiceRouter` 下行路径在写帧前 stamp gwSeq、入 buffer、再 write 到 Channel；
- `GateNettyWebSocketHandler#handleHeartbeat` 解析 `last_client_recv_seq`，调用 `FlowSessionManager.ackSeq(playerId, seq)`，裁剪 buffer + 续写 Redis lastSeqAnchor；
- RESUME 成功后，`FlowSessionManager.resume(...)` 调用 `replayPending(session, channel, fromSeq)` 把 (fromSeq, maxGwSeq] 的帧重新 write 出去。

### player-client demo

- `FlowSessionStore` 增加 `setLastClientRecvSeq` 与持久化；
- 客户端解码 WS/TCP 帧时若 `FLAG_HAS_GW_SEQ` 置位，读取 8 字节 `gwSeq` 并更新本地 `lastClientRecvSeq`；
- 心跳定时器把当前 `lastClientRecvSeq` 写入 `ClientHeartbeat`；
- AUTH 时声明 `client_features = supports_gw_seq | supports_resume_replay`。

### 可观测性

- 新 metrics：`gate_flow_buffer_size` gauge、`gate_flow_buffer_replay_total` counter、`gate_flow_buffer_overflow_total` counter、`gate_flow_buffer_dropped_total` counter；
- 新结构化日志事件：`event=flow.buffer.replay`（含 `replayedCount`, `fromSeq`, `toSeq`）、`event=flow.buffer.overflow`。

## Capabilities

### New Capabilities

- `gate-flow-downstream-buffer`：网关侧下行缓冲、`gwSeq` 协议字段、客户端 ACK 协议、RESUME 重放语义、buffer 容量与回收策略。

### Affected Capabilities (隐式)

- `gate-resume-reconnect`（Phase A，尚未归档）：本变更不删 / 不改其 ADDED Requirements；RESUME 成功后的「重放」是对 Phase A 语义的 **加强**，老客户端不受影响。
- `capability-offline-message`：本变更不动；B3 会进一步精细化 buffer ↔ offline streams 合流策略。

## Impact

- **代码**：`gate-service` 新增 `DownstreamBuffer` 类；`FlowSession`、`FlowSessionManager`、`PlayerService`、`GateNettyWebSocketHandler`、WS/TCP 编解码器修改；`player-client` 同步更新。
- **协议**：`proto/gate_protocol.proto` 兼容新增字段；二进制帧头通过 flag 位扩展，老客户端零侵入。
- **运维**：可配置项 `gate.flow.buffer.{capacity-entries, capacity-bytes, overflow-policy}`；overflow-policy 默认 `drop_oldest`，可切 `force_detach`。
- **性能**：每条下行帧多 8 字节头 + 一次 ring buffer 写入；buffer 内存上限可配，默认 256 帧 × 4 KiB ≈ 1 MiB / 玩家最差情况，10k 玩家约 10 GiB（需在容量评估中说明）。

## Non-Goals

- **不**实现跨实例 RESUME（B2 范围）；
- **不**修改离线 Streams 写入路径（B3 范围）；
- **不**改 game-service 侧的语义；gwSeq 完全是 gate ↔ client 之间的事；
- **不**做下行帧的端到端加密 / 重放攻击防护（沿用现有 message-encryption）。

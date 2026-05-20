## 1. 协议层扩展

- [x] 1.1 `MessageHeader` 新增 `FLAG_HAS_GW_SEQ = 0x2000` 常量与 `gwSeq` 字段（long），含 getter/setter
- [x] 1.2 `proto/gate_protocol.proto` 扩展：`AuthRequest.client_features`、`AuthResponse.server_features`、`ClientHeartbeat.last_client_recv_seq`
- [x] 1.3 重新编译 proto；验证生成的 Java/C# stub 含新字段（`mvn -pl gate-service -am clean compile` 通过）

## 2. 编解码改造

- [x] 2.1 `gate-service/.../ws/codec/WebSocketBinaryEncoder` 与 `WebSocketBinaryDecoder`：根据 flag 写/读可选 8 字节 gwSeq
- [x] 2.2 `gate-service/.../protocol/codec/GameMessageEncoder` 与 `GameMessageDecoder`（TCP 路径）同步改造
- [x] 2.3 `player-client/.../ws/codec/WebSocketBinaryEncoder` 与 `WebSocketBinaryDecoder` 同步改造
- [x] 2.4 `PlayerClientService` 内置 send 函数 / `ClientMessageDecoder` 同步支持 gwSeq 解码

## 3. DownstreamBuffer

- [x] 3.1 新建 `gate-service/.../flow/DownstreamBuffer`：`enqueue` / `ackUpTo` / `drain` / `size` / `totalBytes`
- [x] 3.2 实现 overflow 策略：`drop_oldest`（默认）+ `force_detach`（manager.writeDownstream 收到 FORCE_DETACH 时 close）
- [x] 3.3 `FlowSession` 持有 `nextGwSeq: AtomicLong` 与 `DownstreamBuffer`（null 表示禁用）+ `features`
- [x] 3.4 `FlowSessionManager` 提供 `writeDownstream(session/playerId, message)`、`ackSeq(playerId, seq)`、`replayPending(session, channel, fromSeq)`

## 4. 配置与 features 协商

- [x] 4.1 `GateConfig.FlowConfig` 新增 `BufferConfig`：`enabled` / `capacityEntries` / `capacityBytes` / `overflowPolicy`
- [x] 4.2 `GateConfig.FlowConfig` 新增 `FeaturesConfig`：`advertiseGwSeq` / `advertiseReplay`
- [x] 4.3 `application.yml` 暴露环境变量
- [x] 4.4 `GateNettyWebSocketHandler#handleAuth`：解析 `client_features`，调用 manager 协商；写回 `server_features`

## 5. 下行路径接入 buffer

- [x] 5.1 `PlayerService.sendToPlayer` 委托 `FlowSessionManager.writeDownstream(session, message)`，由 manager 统一 stamp + enqueue + write
- [x] 5.2 entry 持有 `WrappedMessage` 引用（重放走同一编码路径），文档化「入队后 MUST NOT 修改 body」
- [x] 5.3 兼容路径：features.gwSeq=false 时直接 channel.writeAndFlush，不入 buffer

## 6. RESUME 重放

- [x] 6.1 `FlowSessionManager.resume(..., clientFeatures)` 成功路径调用 `replayPending` 在 AuthResponse 之前 flush buffer
- [x] 6.2 重放使用 `channel.write` + `channel.flush()`（由调用方所在 EventLoop 串行）
- [x] 6.3 重放完成更新 `lastSeqAnchor`，落 Redis 字段 `markAckedSeq`

## 7. ACK 路径

- [x] 7.1 `GateNettyWebSocketHandler#handleHeartbeat` 解析 `last_client_recv_seq`，调用 `FlowSessionManager.ackSeq(playerId, seq)`
- [x] 7.2 player-client：WS 解码到 FLAG_HAS_GW_SEQ 时更新 `FlowSessionStore`；心跳定时器写入 `ClientHeartbeat.last_client_recv_seq`
- [x] 7.3 复用 `FlowSessionStore.update(flowId, lastClientRecvSeq)`

## 8. 可观测性

- [x] 8.1 metrics：`gate_flow_buffer_replay_total` / `gate_flow_buffer_overflow_total{policy}` / `gate_flow_buffer_dropped_total{policy}` / `gate_flow_buffer_ack_trimmed_total`
- [x] 8.2 结构化日志：`event=flow.buffer.replay`、`flow.buffer.overflow`
- [x] 8.3 `/debug/flows` 端点输出 `buffer.size / buffer.totalBytes / minGwSeq / maxGwSeq / overflowPolicy / nextGwSeq / features`

## 9. 测试

- [x] 9.1 单元：`DownstreamBufferTest`：enqueue/ackUpTo/drain/overflow 双策略 (7 用例)
- [x] 9.2 单元：`FlowSessionManager` 补 5 个新用例覆盖：features 协商 / writeDownstream stamp / ackSeq trim / RESUME replay 全/无重放 / 老客户端兜底
- [ ] 9.3 单元：features 协商矩阵专项测试（含 advertise=false 场景）— 由 FlowSessionManagerTest 部分覆盖，独立专项延后
- [ ] 9.4 codec：往返编解码带/不带 FLAG_HAS_GW_SEQ 的帧 — 延后（生产代码已通过 compile + 集成路径验证）

## 10. 文档与归档

- [ ] 10.1 GateDemo `docs/flow-session-resume.md` 补章节：buffer 行为、容量评估、客户端 ACK 集成（统一在 Phase B 完工后补）
- [x] 10.2 `openspec validate add-flow-downstream-buffer --strict`
- [ ] 10.3 等 Phase A 归档后，再 `openspec archive add-flow-downstream-buffer`

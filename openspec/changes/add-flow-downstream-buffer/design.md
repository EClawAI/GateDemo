## 1. 数据流概览

```text
                                       client ACK (lastClientRecvSeq, via heartbeat)
                                                 │
                ┌──── game-service ──── gRPC ────▼─── gate-service ────────────────┐
                │                                                                  │
                │                                  ┌──────────────────────────────┐│
                │                                  │ FlowSession                  ││
                │                                  │  · nextGwSeq (AtomicLong)    ││
                │                                  │  · DownstreamBuffer (ring)   ││
                │                                  │  · lastSeqAnchor             ││
                │                                  └──────────┬───────────────────┘│
                │                                             │ stamp+enqueue      │
                │  send → PlayerService → DownstreamBuffer ───┤                    │
                │                                             │                    │
                │                                             ▼                    │
                │                                       Netty Channel ────► client │
                └───────────────────────────────────────────────────────────────────┘
                                  ▲
                                  │ RESUME successful: manager.replayPending(fromSeq)
                                  └──────── reads buffer entries (fromSeq, latest]
```

## 2. 协议帧扩展（向后兼容）

### 标志位

`MessageHeader.flags` 新增 `FLAG_HAS_GW_SEQ = 0x2000`。置位时帧体在 16 字节头后多 8 字节 big-endian unsigned `gwSeq`，再接 `bodyLength` 字节 body。

```text
| 2 bytes | 2 bytes  | 4 bytes   | 4 bytes    | 4 bytes   | 8 bytes  | bodyLength bytes |
| flags   | sequence | messageId | bodyLength | requestId | gwSeq?   | body             |
                                                                  └─ only if FLAG_HAS_GW_SEQ
```

旧客户端：永不读 `gwSeq`；服务器仅在客户端 AUTH 时声明 `supports_gw_seq` 才会 stamp 该 flag，否则保持 16 字节头。

### proto 字段

- `AuthRequest.client_features`（uint32）：
  - `bit 0 = 0x1` → `supports_gw_seq`
  - `bit 1 = 0x2` → `supports_resume_replay`（缺一不可，本变更两位必须同时声明才会启用 buffer/重放）
- `AuthResponse.server_features`（uint32）：服务端实际启用项（client & server 的 AND）；客户端按服务端反馈工作。
- `ClientHeartbeat.last_client_recv_seq`（uint64）：客户端已成功处理的最大 gwSeq。

### 启用矩阵

| client.bit0 | client.bit1 | server.bit0 | server.bit1 | 行为 |
|-------------|-------------|-------------|-------------|------|
| ✗ | ✗ | – | – | 老客户端，零 gwSeq，不入 buffer，行为同 Phase A |
| ✓ | ✗ | ✓ | ✗ | gwSeq stamped，但 RESUME 不重放（客户端不要求） |
| ✓ | ✓ | ✓ | ✓ | 完整 buffer + RESUME 重放 |

## 3. DownstreamBuffer

```text
┌────────────────────────────────────────────────────────────────────────┐
│ DownstreamBuffer (per FlowSession)                                     │
│                                                                        │
│  capacity-entries:  256   (gate.flow.buffer.capacity-entries)          │
│  capacity-bytes:    4 MiB (gate.flow.buffer.capacity-bytes)            │
│  overflow:          drop_oldest | force_detach                         │
│                                                                        │
│  entries:                                                              │
│    [ {gwSeq, encodedFrameBytes, sizeBytes, enqueuedAt} , ... ]         │
│                                                                        │
│  ackUpTo(seq) → 丢弃所有 gwSeq <= seq 的 entries                       │
│  enqueue(seq, bytes) → 追加；满则按 overflow 策略                      │
│  drain(fromSeq) → 返回 [fromSeq+1, latest] 的所有 entries（按序）       │
└────────────────────────────────────────────────────────────────────────┘
```

- **结构**：`ArrayDeque<BufferedFrame>` + 双计数器（`totalEntries`, `totalBytes`），单线程访问（由 `FlowSessionManager` 串行化）。
- **gwSeq 单调递增**：从 1 开始（0 表示「尚未发过」）；`AtomicLong.incrementAndGet()`。
- **裁剪策略**：每次 client ACK 触发；scheduled task 不主动裁剪。
- **overflow 策略**：
  - `drop_oldest`（默认）：踢掉最老的 entry，记 metric；客户端 RESUME 时可能拿到不完整的重放，需要客户端自己识别 gap（gwSeq 跳跃）后走全量恢复（向 game-service 重发查询请求）。
  - `force_detach`：buffer 满即主动关 Channel，让 FlowSession 进入 DETACHED；适合「宁可断也不能丢」的场景（如金融/付费）。

## 4. RESUME 重放流程

```text
client sends AuthRequest { flow_id, last_client_recv_seq, client_features }
                                  │
                                  ▼
   FlowSessionManager.resume(flowId, playerId, lastSeq, channel)
                                  │
                  ┌───────────────┴───────────────┐
                  │     existing RESUME checks    │ (Phase A)
                  │     channel.attach(...)        │
                  └───────────────┬───────────────┘
                                  ▼
   if (session.features.replayEnabled && lastSeq < session.maxGwSeq):
       List<BufferedFrame> pending = buffer.drain(after = lastSeq);
       for frame in pending:
           channel.write(frame.bytes);
       channel.flush();
       emitMetric("gate_flow_buffer_replay_total", count=pending.size())
       log "event=flow.buffer.replay fromSeq=... toSeq=... count=..."

   AuthResponse { flow_id, resume_status=RESUMED, server_features }
```

- **同 EventLoop 串行**：重放写在新 Channel 的 EventLoop 线程；AuthResponse 之前刷出，保证客户端收到 AuthResponse 时已经在新 Channel 上看见所有补发帧。
- **顺序保证**：buffer 是按 enqueue 顺序的；只要 game-service → gate 的上游有序（gRPC bidi stream 保证），下行就有序。
- **裁剪锚点**：重放完成后将 `session.lastSeqAnchor = max(lastSeq, fromSeq)`；后续客户端 ACK 仍会继续裁。

## 5. 客户端 ACK 路径

```text
client decoder reads frame:
    if FLAG_HAS_GW_SEQ:
        gwSeq = read8(); lastClientRecvSeq = max(lastClientRecvSeq, gwSeq)
        FlowSessionStore.setLastClientRecvSeq(lastClientRecvSeq)
    deliver to listener

heartbeat timer (every 30s, configurable):
    send ClientHeartbeat { last_client_recv_seq = current }

gate handler (handleHeartbeat):
    FlowSessionManager.ackSeq(playerId, msg.last_client_recv_seq)
        → session.buffer.ackUpTo(seq)
        → session.lastSeqAnchor = max(session.lastSeqAnchor, seq)
        → redisStore.markAckedSeq(flowId, seq)（合并 detachedAt 字段的同类 HSET）
```

- **多频度 ACK 取舍**：本变更只在心跳里捎带 ACK；不引入独立 ACK 帧。心跳间隔 30s ≈ buffer 容量上限的关键约束 — 在 30s 内若发生爆量推送可能 overflow，建议 overflow_policy=force_detach 的场景把心跳间隔降到 5-10s。

## 6. 配置项

| key | default | 说明 |
|-----|---------|------|
| `gate.flow.buffer.enabled` | `true` | 全局开关；off 时退化为 Phase A 行为 |
| `gate.flow.buffer.capacity-entries` | `256` | per-FlowSession entries 上限 |
| `gate.flow.buffer.capacity-bytes` | `4194304` (4 MiB) | per-FlowSession bytes 上限（取 min） |
| `gate.flow.buffer.overflow-policy` | `drop_oldest` | `drop_oldest` \| `force_detach` |
| `gate.flow.features.advertise-gw-seq` | `true` | 服务端是否在 server_features 反馈支持 |
| `gate.flow.features.advertise-replay` | `true` | 同上，replay |

## 7. 与 Phase A 的兼容

| 维度 | Phase A 行为 | B1 行为 |
|------|-------------|---------|
| 客户端不带 `flow_id` | NEW，mint flowId | 同 |
| 客户端带 `flow_id`，RESUME 成功 | 仅换绑 Channel | 换绑 + replay buffer（若启用） |
| 老客户端（不声明 features） | 同 Phase A | 同 Phase A，buffer 不入 |
| `FlowSession.lastSeqAnchor` | 仅记录 client 发来的 `last_client_recv_seq` | 还驱动 buffer 裁剪 |

## 8. 风险

- **buffer 内存膨胀**：10k 玩家 × 4 MiB/玩家 = 40 GiB 上限；实际由消息频度决定。需要在 readme 中说明上限并给容量评估示例。
- **gwSeq 64-bit 不溢出**：每秒 100w 条下行也要 2.9 万年才溢出，可忽略。
- **客户端 ACK 丢失**：若客户端 ACK 持续不到达（DETACHED 中），buffer 在 TTL 内不会裁剪 → DETACHED TTL 超时 → flow.destroyed 一并销毁 buffer，符合预期。
- **gap 检测**：客户端解码若 `gwSeq != lastClientRecvSeq + 1` → 发生 gap（drop_oldest 策略下可能出现）。客户端应记录该事件，必要时主动重连 NEW 触发全量 resync。
- **proto3 default uint64 = 0** 与「首次心跳」难区分：因此约定 0 = 「未收到任何带 gwSeq 的帧」，服务端不据此裁剪。

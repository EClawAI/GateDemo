# gate-flow-offline-merge

## ADDED Requirements

### Requirement: 下行消息按 FlowSession 状态路由

The gateway SHALL route each downstream message (game→client) according to the player's current `FlowSession` state:

- ATTACHED + active Channel → in-memory `DownstreamBuffer` + direct Channel write (per `gate-flow-downstream-buffer`);
- DETACHED (local session exists, channel absent) → `OfflineMessageService.storeForDetached(session, message)`, which SHALL stamp the message with a fresh `gwSeq` from `FlowSession.nextGwSeq` and persist to the flow-scoped Redis Stream;
- No FlowSession at all → `OfflineMessageService.storeForOffline(playerId, message)`, persisted to the player-scoped fallback Redis Stream WITHOUT a `gwSeq`.

#### Scenario: ATTACHED 不走 offline 路径

- **GIVEN** player A is ATTACHED with an active channel
- **WHEN** game-service pushes a message via `PlayerService.sendToPlayer`
- **THEN** the message is enqueued into the in-memory `DownstreamBuffer` and written to the channel, and no entry is appended to `game:offline:flow:{flowId}`.

#### Scenario: DETACHED 状态下消息写入 flowId 隔离 stream

- **GIVEN** player A has a FlowSession in state DETACHED (no active channel) with `flowId=F1`
- **WHEN** game-service pushes 3 messages to player A while DETACHED
- **THEN** 3 entries are appended to `game:offline:flow:F1`, each carrying a unique increasing `gw_seq` field, and `sendToPlayer` returns `true`.

#### Scenario: 无 FlowSession 走 playerId 兜底 stream

- **GIVEN** player A has no local FlowSession (destroyed / never logged in on this gate) and Redis also has no record
- **WHEN** game-service pushes a message
- **THEN** an entry is appended to `game:offline:messages:{playerId}` without `gw_seq`, and `sendToPlayer` returns `true`.

### Requirement: RESUME 时合流 DownstreamBuffer 与 offline stream

The gateway SHALL, on a successful RESUME (`FlowResumeOutcome.RESUMED`), replay un-acknowledged frames in `gwSeq` ascending order by:

1. First replaying entries from the in-memory `DownstreamBuffer` (per B1 `replayPending`);
2. Then calling `OfflineMessageService.replayAndMerge(session, channel, fromSeq)` where `fromSeq = max(lastClientRecvSeq, bufferReplayResult.toSeq)`;
3. The offline replay SHALL filter entries with `gw_seq > fromSeq`, write them in ascending order on the new channel, and `XDEL` each entry after successful write;
4. Entries without a `gw_seq` field (legacy or fallback) SHALL be treated as `gw_seq=0` and delivered in stream order.

#### Scenario: DETACH 期间 push 后 RESUME 收到全部消息

- **GIVEN** player A has FlowSession `F1`, in-memory buffer holds frames with `gwSeq=[1..5]`, client last ACKed `gwSeq=3`;
- **AND** during the DETACHED window, game-service pushes 4 more messages stored in `game:offline:flow:F1` with `gwSeq=[6..9]`;
- **WHEN** client RESUMEs with `last_client_recv_seq=3`
- **THEN** the new channel receives frames in order `gwSeq=4,5,6,7,8,9` (6 frames total), the buffer retains `gwSeq=4,5` until next ACK, and `game:offline:flow:F1` is empty after replay.

#### Scenario: 合流防御性过滤重叠

- **GIVEN** by design buffer ∪ offline are disjoint by `gwSeq`, but defensively the merge MUST filter `gwSeq > fromSeq`
- **WHEN** offline contains an entry with `gwSeq <= fromSeq` (due to legacy / race)
- **THEN** that entry SHALL NOT be re-delivered, and a `gate_flow_offline_dropped_total{reason="dup_or_acked"}` counter is incremented.

### Requirement: destroy / cross-evict 前 flush 未 ACK buffer 到 offline

The gateway SHALL, before destroying a FlowSession with reason `detached_ttl` or `cross_takeover` (and only those reasons), flush all buffer entries with `gw_seq > lastSeqAnchor` to `game:offline:flow:{flowId}` via `OfflineMessageService.flushBufferToOffline(session)`. NEW takeover (`new_takeover`) SHALL NOT flush — the player explicitly relogged and the old flow's frames are obsolete.

#### Scenario: detached_ttl 触发 flush

- **GIVEN** FlowSession `F1` enters DETACHED with un-ACKed buffer entries `gwSeq=[10..15]`;
- **AND** `gate.flow.offline.flush-on-destroy=true`;
- **WHEN** `detached-ttl-seconds` elapses and `scanDetached` calls `destroy(F1, "detached_ttl")`
- **THEN** entries `gwSeq=10..15` are persisted to `game:offline:flow:F1` and the in-memory buffer is released.

#### Scenario: NEW takeover 不 flush

- **GIVEN** player A has FlowSession `F1` with buffered `gwSeq=[10..15]` un-ACKed;
- **WHEN** player A logs in again with a new auth, creating FlowSession `F2` (NEW path)
- **THEN** `F1` is destroyed with reason `new_takeover`, NO entries are written to `game:offline:flow:F1`, and the buffer is released.

### Requirement: 跨实例 RESUME 时恢复 nextGwSeq

The gateway SHALL, when a cross-instance RESUME succeeds (B2 `RESUMED_OWNER_CHANGED` with no local FlowSession), initialize the new local `FlowSession.nextGwSeq` to `max(record.lastSeqAnchor, OfflineMessageService.maxGwSeq(flowId))` so subsequent server-side pushes never re-use a `gwSeq` already occupied by entries in the offline stream.

#### Scenario: 跨实例 RESUME 后下一次 push 的 gwSeq 大于 offline 最大

- **GIVEN** flow `F1` was previously owned by `gate-01`; offline stream `game:offline:flow:F1` contains entries with `gw_seq=[1..7]`; Redis record's `lastSeqAnchor=3`;
- **WHEN** client RESUMEs to `gate-02`, triggering cross-takeover with no local session on `gate-02`
- **THEN** the new local FlowSession's `nextGwSeq` SHALL be initialized so the next push gets `gwSeq=8` (i.e. `max(3, 7) + 1`).

### Requirement: 离线消息保留与超阈值 RELOGIN

The gateway SHALL respect `gate.flow.offline.retention-days` (default 7) by setting a corresponding Redis TTL on every write to `game:offline:flow:*` and `game:offline:messages:*`. When `OfflineMessageService.onPlayerOnline(playerId, channel)` is called on NEW login and the count of fallback entries (`game:offline:messages:{playerId}`) exceeds `gate.flow.offline.relogin-threshold` (default 200), the gateway SHALL trigger the existing relogin notification (`ErrorResponse{code=RELOGIN_REQUIRED}`) and `XDEL` the fallback stream.

#### Scenario: 离线消息 TTL 设置

- **GIVEN** `gate.flow.offline.retention-days=7`
- **WHEN** any entry is appended to `game:offline:flow:F1` or `game:offline:messages:42`
- **THEN** the corresponding key has a TTL ≤ 7 days (renewed on each write).

#### Scenario: 超阈值触发 RELOGIN

- **GIVEN** player 42 has 250 entries in `game:offline:messages:42` and `gate.flow.offline.relogin-threshold=200`
- **WHEN** player 42 NEW-logs in
- **THEN** an `ErrorResponse{code=RELOGIN_REQUIRED}` is pushed once, the entire `game:offline:messages:42` is deleted, and `gate_flow_offline_dropped_total{reason="relogin_threshold"}` is incremented by 250.

### Requirement: 可观测性

The gateway SHALL emit metrics covering the offline merge path:

- `gate_flow_offline_stored_total{reason="detached"|"no_session"|"flush"}` — entries written;
- `gate_flow_offline_replayed_total` — entries successfully replayed on RESUME;
- `gate_flow_offline_dropped_total{reason}` — entries dropped (dup_or_acked, relogin_threshold, redis_unavailable);
- `gate_flow_offline_flush_total{reason="detached_ttl"|"cross_takeover"}` — flush invocations;

and structured log events:

- `event=flow.offline.stored flowId=… playerId=… gwSeq=… reason=…`;
- `event=flow.offline.replayed flowId=… playerId=… count=… fromSeq=… toSeq=…`;
- `event=flow.offline.flushed flowId=… playerId=… count=… reason=…`.

#### Scenario: replayAndMerge 写出 metric

- **GIVEN** offline stream `game:offline:flow:F1` has 4 entries with `gw_seq=[8..11]`, client RESUME with `last_client_recv_seq=7`
- **WHEN** `replayAndMerge` succeeds
- **THEN** `gate_flow_offline_replayed_total` increments by 4, log event `flow.offline.replayed count=4 fromSeq=7 toSeq=11` is emitted, and the 4 stream entries are XDELed.

### Requirement: Redis 不可用时降级

The gateway SHALL, when any Redis call within `OfflineMessageService` throws `DataAccessException`, log a warning + emit `gate_flow_offline_dropped_total{reason="redis_unavailable"}` and return `false` / empty result without throwing to the caller. The in-memory B1 buffer path SHALL continue to function unaffected.

#### Scenario: Redis 故障不影响 ATTACHED 主路径

- **GIVEN** Redis is unreachable
- **WHEN** ATTACHED player A receives a push
- **THEN** the message is still written to the channel via `FlowSessionManager.writeDownstream` and B1 buffer; only the offline DETACHED / no-session path returns `false`.

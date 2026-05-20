# gate-flow-observability

## ADDED Requirements

### Requirement: 命名统一为 `gate_flow_<noun>_<verb>` 前缀

The gateway SHALL adopt a consistent metric naming convention `gate_flow_<noun>_<verb>` for all new and renamed metrics introduced by this change. All metrics defined in this capability SHALL conform to this pattern:

- `gate_flow_takeover_total`
- `gate_flow_resume_total`
- `gate_flow_replay_total`
- `gate_flow_latency_resume_ms`
- `gate_flow_latency_cross_ready_ms`

The following legacy metrics SHALL NOT be emitted by the gateway after this change is implemented (they are replaced by the new metrics above):

- `gate_flow_total{event="cross_takeover", reason=*}` — replaced by `gate_flow_takeover_total{result}`
- `gate_flow_buffer_replay_total` — replaced by `gate_flow_replay_total{source="buffer", outcome="replayed"}`
- `gate_flow_resume_latency_ms` (legacy untagged Timer) — replaced by `gate_flow_latency_resume_ms{kind}`

Other existing `gate_flow_*` metrics not listed above SHALL continue to be emitted unchanged.

#### Scenario: legacy cross_takeover counter is no longer emitted

- **GIVEN** the change is deployed and a cross-instance takeover occurs
- **WHEN** the takeover completes
- **THEN** the meter registry SHALL NOT contain any Counter named `gate_flow_total` with tag combination `event="cross_takeover"`
- **AND** the registry SHALL contain `gate_flow_takeover_total{result=...}` with the appropriate value incremented

#### Scenario: legacy buffer replay counter is no longer emitted

- **GIVEN** the change is deployed and a RESUME triggers buffer replay
- **WHEN** the replay writes frames to the channel
- **THEN** the meter registry SHALL NOT contain any Counter named `gate_flow_buffer_replay_total`
- **AND** the registry SHALL contain `gate_flow_replay_total{source="buffer", outcome="replayed"}` with the count of frames written

#### Scenario: legacy resume latency timer is no longer emitted

- **GIVEN** the change is deployed and a RESUME completes
- **WHEN** latency is recorded
- **THEN** the meter registry SHALL NOT contain any Timer named `gate_flow_resume_latency_ms`
- **AND** the registry SHALL contain `gate_flow_latency_resume_ms{kind=...}` with the recorded latency

### Requirement: FlowMetrics 集中维护指标常量与映射

The gateway SHALL provide a `com.clawai.gatedemo.gate.flow.metrics.FlowMetrics` class that centralizes:

- Metric names (`gate_flow_takeover_total`, `gate_flow_resume_total`, `gate_flow_latency_resume_ms`, `gate_flow_latency_cross_ready_ms`, `gate_flow_replay_total`);
- Tag keys (`result`, `kind`, `outcome`, `source`) and their permitted enum values;
- Histogram SLO buckets (`10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s`) applied to both latency Timers;
- Pure static mapping functions: `mapTakeoverResult(rawReason)` and `mapResumeOutcome(rejectReason)`.

The class SHALL cache `Meter` handles by tag tuple so that hot-path emission has O(1) lookup with no reflection.

When the injected `MeterRegistry` is `null`, or when the corresponding sub-switch in `ObservabilityConfig` is `false`, the class SHALL return a no-op Meter that performs no work and registers nothing.

#### Scenario: mapTakeoverResult covers all known reasons

- **GIVEN** the existing `CrossTakeoverResult` reason enum
- **WHEN** `FlowMetrics.mapTakeoverResult(reason)` is invoked
- **THEN** the result SHALL be:
  - `OWNER_SAME` → `succeeded`
  - `OWNER_CHANGED` → `succeeded`
  - `EXPIRED` → `metadata_missing`
  - `REDIS_UNAVAILABLE` → `redis_unavailable`
  - any unknown value → `rejected` (fallback bucket)

#### Scenario: Meter handles are cached per tag combination

- **GIVEN** `FlowMetrics` instance over a `SimpleMeterRegistry`
- **WHEN** `takeover("succeeded")` is invoked 10000 times on the same instance
- **THEN** only one `Counter` SHALL be registered in the registry (verified by `registry.find(name).counters().size() == 1`)
- **AND** the counter value SHALL equal 10000

#### Scenario: noop factory never throws

- **GIVEN** `FlowMetrics flowMetrics = FlowMetrics.noop()`
- **WHEN** any combination of `takeover/resumeTotal/resumeLatency/crossReadyLatency/replay` is invoked
- **THEN** no exception is thrown and no state is leaked

#### Scenario: sub-switch disabled returns noop

- **GIVEN** `gate.flow.observability.takeover-total-enabled = false`
- **WHEN** `FlowMetrics.takeover("succeeded")` is invoked
- **THEN** the returned counter SHALL be a noop instance (any `increment()` call is silently dropped)
- **AND** the meter registry SHALL NOT contain `gate_flow_takeover_total`

### Requirement: `gate_flow_takeover_total` Counter

The gateway SHALL emit a Counter `gate_flow_takeover_total` with tag `result` whenever a cross-instance takeover is attempted, observed, or published. The `result` tag SHALL take exactly one of six values:

| Scenario | result |
|---|---|
| `RedisFlowStore.crossTakeover` returns `RESUMED_OWNER_SAME` | `succeeded` |
| `RedisFlowStore.crossTakeover` returns `RESUMED_OWNER_CHANGED` | `succeeded` |
| `RedisFlowStore.crossTakeover` returns `EXPIRED` | `metadata_missing` |
| `RedisFlowStore.crossTakeover` returns `REDIS_UNAVAILABLE` | `redis_unavailable` |
| Future business-side rejection paths | `rejected` |
| `FlowEvictListener` receives an inbound evict for a local flow | `evicted_by_remote` |
| `FlowEvictPublisher.publish` issues an outbound evict | `evicted_remote` |

`succeeded` and `evicted_remote` SHALL be emitted from the SAME instance that initiates a successful cross-takeover (different views of the same action). Operators querying "total cross-takeover count" SHALL filter `result!="evicted_remote"` to avoid double-counting.

#### Scenario: succeeded result on cross-takeover with owner change

- **GIVEN** a RESUME request that crosses gate instances and Lua returns `OWNER_CHANGED`
- **WHEN** `FlowSessionManager.resume(...)` completes
- **THEN** `gate_flow_takeover_total{result="succeeded"}` SHALL increment by exactly 1
- **AND** `gate_flow_takeover_total{result="evicted_remote"}` SHALL increment by exactly 1 (publish-side view)

#### Scenario: metadata_missing on expired flow

- **GIVEN** a RESUME request whose flow has been GC'd by Redis TTL
- **WHEN** `crossTakeover` returns `EXPIRED`
- **THEN** `gate_flow_takeover_total{result="metadata_missing"}` SHALL increment by 1

#### Scenario: evicted_by_remote on inbound Pub/Sub event

- **GIVEN** an active local FlowSession owned by `gate-A`
- **WHEN** `FlowEvictListener` receives `gate:flow:evict` payload `{flowId, newOwner=gate-B}` matching the local flow
- **THEN** `gate_flow_takeover_total{result="evicted_by_remote"}` SHALL increment by 1

#### Scenario: redis_unavailable on Lua failure

- **GIVEN** Redis is unreachable
- **WHEN** `crossTakeover` is invoked
- **THEN** `gate_flow_takeover_total{result="redis_unavailable"}` SHALL increment by 1

### Requirement: `gate_flow_resume_total` Counter

The gateway SHALL emit Counter `gate_flow_resume_total` with two tags `kind` and `outcome`:

- `kind` ∈ {`same_gw`, `cross_gw`, `new`}
- `outcome` ∈ {`succeeded`, `rejected_expired`, `rejected_mismatch`, `rejected_owner_other`, `degraded_to_new`, `cancelled`, `timeout`}

`kind="new"` SHALL be emitted by `FlowSessionManager.newFlow(...)` with `outcome="succeeded"` to provide full counting of all session establishments (including first-time logins).

`outcome="cancelled"` SHALL be emitted when the client channel is closed during RESUME processing.

`outcome="timeout"` SHALL be emitted when total RESUME processing time exceeds `gate.flow.observability.resume-timeout-ms` (default 3000ms).

#### Scenario: same-instance RESUME success

- **GIVEN** a RESUME on the same gate instance where the flow already exists locally
- **WHEN** RESUME succeeds
- **THEN** `gate_flow_resume_total{kind="same_gw", outcome="succeeded"}` SHALL increment by 1
- **AND** no `cross_gw` counter SHALL be touched

#### Scenario: cross-instance RESUME success

- **GIVEN** a RESUME that triggers cross-instance takeover
- **WHEN** RESUME succeeds
- **THEN** `gate_flow_resume_total{kind="cross_gw", outcome="succeeded"}` SHALL increment by 1

#### Scenario: RESUME rejected due to owner mismatch

- **GIVEN** a RESUME whose record exists but `playerIdFromToken != record.playerId`
- **WHEN** RESUME rejects with `MISMATCH`
- **THEN** `gate_flow_resume_total{kind="same_gw", outcome="rejected_mismatch"}` SHALL increment by 1

#### Scenario: NEW path counts as kind="new"

- **GIVEN** a NEW login (no `flow_id` in AUTH)
- **WHEN** `FlowSessionManager.newFlow(...)` succeeds
- **THEN** `gate_flow_resume_total{kind="new", outcome="succeeded"}` SHALL increment by 1

#### Scenario: cancelled outcome on channel close during RESUME

- **GIVEN** a RESUME in progress
- **WHEN** the client channel is closed before RESUME completes
- **THEN** `gate_flow_resume_total{kind="same_gw"|"cross_gw", outcome="cancelled"}` SHALL increment by 1

#### Scenario: timeout outcome when exceeding configured threshold

- **GIVEN** `gate.flow.observability.resume-timeout-ms = 500`
- **AND** a RESUME that takes 800ms to complete
- **WHEN** RESUME finishes
- **THEN** `gate_flow_resume_total{kind=..., outcome="timeout"}` SHALL increment by 1
- **AND** `outcome="succeeded"` SHALL NOT increment for the same RESUME (mutually exclusive)

### Requirement: 延迟 Timer 按 kind 分桶 + 跨实例独立 Timer

The gateway SHALL:

1. Provide `gate_flow_latency_resume_ms` Timer tagged on `kind` (`same_gw` / `cross_gw` / `new`);
2. Provide a dedicated Timer `gate_flow_latency_cross_ready_ms` (no tag) measuring "AUTH received → offline merge done & first downstream frame writable" for cross-instance RESUME;
3. Configure both Timers with SLO buckets: `10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s` and `publishPercentileHistogram=true`;
4. SHALL NOT register the legacy `gate_flow_resume_latency_ms` (untagged) Timer.

#### Scenario: same-gw RESUME latency recorded

- **GIVEN** a successful same-gw RESUME taking 35ms
- **WHEN** `FlowSessionManager.resume(...)` completes
- **THEN** `gate_flow_latency_resume_ms{kind="same_gw"}` SHALL have count=1 with value in the `25ms..50ms` bucket
- **AND** `gate_flow_latency_cross_ready_ms` count SHALL remain 0

#### Scenario: cross-gw RESUME records both Timers

- **GIVEN** a successful cross-gw RESUME taking 180ms
- **WHEN** `FlowSessionManager.resume(...)` completes
- **THEN** `gate_flow_latency_resume_ms{kind="cross_gw"}` SHALL have count=1 with value in the `100ms..250ms` bucket
- **AND** `gate_flow_latency_cross_ready_ms` SHALL have count=1 with the same value

#### Scenario: NEW path also records latency

- **GIVEN** a NEW login that took 12ms
- **WHEN** `FlowSessionManager.newFlow(...)` completes
- **THEN** `gate_flow_latency_resume_ms{kind="new"}` SHALL have count=1

#### Scenario: histogram SLO buckets are configurable

- **GIVEN** `gate.flow.observability.histogram-slo = "5ms,50ms,500ms"`
- **WHEN** the application context starts
- **THEN** the Timer SLO buckets SHALL match the configured values (3 buckets instead of the default 9)

### Requirement: `gate_flow_replay_total` Counter

The gateway SHALL emit Counter `gate_flow_replay_total` with tags `source` ∈ {`buffer`, `offline`, `merged`} and `outcome` ∈ {`replayed`, `skipped_acked`, `dropped`} from the following sources:

| 调用点 | source | outcome 触发条件 |
|---|---|---|
| `DownstreamBuffer.ackUpTo` trimmed frames during RESUME | `buffer` | `skipped_acked` per trimmed frame |
| `FlowSessionManager.replayPending` writes a frame | `buffer` | `replayed` per write |
| `replayPending` finds channel inactive before flush | `buffer` | `dropped` for the remaining frames |
| `OfflineMessageService.replayAndMerge` filters out `gwSeq ≤ fromSeq` | `offline` | `skipped_acked` |
| `OfflineMessageService.replayAndMerge` writes a frame | `offline` | `replayed` |
| `OfflineMessageService.replayAndMerge` write fails / channel inactive | `offline` | `dropped` |
| `FlowSessionManager.resume` cross-gw success end-of-flow summary | `merged` | `replayed` (count = buffer.replayed + offline.replayed) |

`OfflineMessageService` SHALL emit through a package-friendly method `FlowSessionManager.recordReplay(source, outcome, count)` rather than holding a direct `MeterRegistry` reference.

`source=merged` SHALL only ever use `outcome=replayed` (the merged view aggregates already-detailed buffer/offline outcomes).

#### Scenario: buffer replay with mixed outcomes

- **GIVEN** a RESUME where the buffer holds frames seq=10..20, the client ACK is seq=15, the channel is active
- **WHEN** `replayPending` runs after `ackUpTo(15)`
- **THEN** `gate_flow_replay_total{source="buffer", outcome="skipped_acked"}` SHALL increase by 5 (frames 10..15)
- **AND** `gate_flow_replay_total{source="buffer", outcome="replayed"}` SHALL increase by 5 (frames 16..20)

#### Scenario: offline replay records replayed and skipped separately

- **GIVEN** offline stream `[seq=5, seq=7, seq=9]`, fromSeq=6
- **WHEN** `replayAndMerge(session, channel, fromSeq=6)` runs
- **THEN** `gate_flow_replay_total{source="offline", outcome="skipped_acked"}` SHALL increase by 1 (seq=5)
- **AND** `gate_flow_replay_total{source="offline", outcome="replayed"}` SHALL increase by 2 (seq=7, 9)

#### Scenario: dropped on inactive channel

- **GIVEN** a RESUME path where channel is closed between merge start and a per-frame write
- **WHEN** `replayAndMerge` detects the inactive channel
- **THEN** `gate_flow_replay_total{source="offline", outcome="dropped"}` SHALL reflect the frames that could not be delivered

#### Scenario: merged source summary on cross-gw success

- **GIVEN** a successful cross-gw RESUME with buffer.replayed=5 and offline.replayed=12
- **WHEN** RESUME completes
- **THEN** `gate_flow_replay_total{source="merged", outcome="replayed"}` SHALL increase by 17

### Requirement: 独立 logger `gate.cross.event`

The gateway SHALL emit cross-instance lifecycle events via a dedicated SLF4J logger `gate.cross.event` (top-level, parallel to `gate.flow.event`):

- Outbound `gate.cross.event`: `event=cross.takeover.publish flowId=... previousOwnerGateId=... newOwnerGateId=...`
- Inbound `gate.cross.event`: `event=cross.evict.received flowId=... newOwnerGateId=... localOwner=...`
- Result `gate.cross.event`: `event=cross.takeover.result flowId=... result=succeeded|metadata_missing|... ageMs=... bufferReplayed=N offlineReplayed=M`

`gate.flow.event` content for normal RESUME flow SHALL remain unchanged for backward compatibility, including the existing `event=flow.resumed crossTakeover=true previousOwnerGateId=...` entry.

The behavior SHALL be toggleable via `gate.flow.observability.cross-logger-enabled` (default `true`); when `false` no events are emitted to `gate.cross.event` (but `gate.flow.event` is unchanged).

#### Scenario: cross-takeover writes both loggers

- **GIVEN** observability enabled (default) and a successful cross-gw RESUME
- **WHEN** `FlowSessionManager.resume(...)` completes
- **THEN** exactly one log entry containing `event=cross.takeover.result` SHALL be emitted to logger `gate.cross.event`
- **AND** the existing `event=flow.resumed crossTakeover=true previousOwnerGateId=...` entry SHALL be emitted to `gate.flow.event` unchanged

#### Scenario: cross-logger disabled keeps single-logger behavior

- **GIVEN** `gate.flow.observability.cross-logger-enabled=false`
- **WHEN** a successful cross-gw RESUME completes
- **THEN** no log entry SHALL be emitted to `gate.cross.event`
- **AND** `gate.flow.event` content SHALL be unchanged

### Requirement: ObservabilityConfig per-metric 开关

The gateway SHALL add `GateConfig.FlowConfig.ObservabilityConfig` exposing the following fields under `gate.flow.observability.*`:

| 字段 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | master switch；false 时所有 sub-switch 无效，整体走 noop |
| `takeover-total-enabled` | `true` | `gate_flow_takeover_total` |
| `resume-total-enabled` | `true` | `gate_flow_resume_total` |
| `replay-total-enabled` | `true` | `gate_flow_replay_total` |
| `latency-enabled` | `true` | 两个 Timer |
| `cross-logger-enabled` | `true` | `gate.cross.event` |
| `histogram-slo` | `"10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s"` | SLO bucket 列表（逗号分隔） |
| `resume-timeout-ms` | `3000` | `outcome=timeout` 触发阈值 |

Environment variable bindings SHALL follow Spring convention (`GATE_FLOW_OBSERVABILITY_*`).

#### Scenario: master switch disables all new metrics

- **GIVEN** `gate.flow.observability.enabled=false`
- **WHEN** the application context starts and a RESUME happens
- **THEN** the registry SHALL NOT contain any of: `gate_flow_takeover_total`, `gate_flow_resume_total`, `gate_flow_latency_resume_ms`, `gate_flow_latency_cross_ready_ms`, `gate_flow_replay_total`
- **AND** no entry SHALL be emitted to `gate.cross.event`

#### Scenario: per-metric switch isolates effect

- **GIVEN** `enabled=true` and `takeover-total-enabled=false` and all other sub-switches `true`
- **WHEN** a cross-gw RESUME completes
- **THEN** the registry SHALL NOT contain `gate_flow_takeover_total`
- **AND** the registry SHALL contain `gate_flow_resume_total{kind="cross_gw", outcome="succeeded"}` with value 1
- **AND** the registry SHALL contain `gate_flow_latency_cross_ready_ms` with count 1

### Requirement: 不影响保留指标与 lifecycle 日志

The change SHALL NOT modify the emission of the following meters and loggers:

- `gate_flow_total{event=new|resumed|destroyed|detached|buffer_dropped|buffer_overflow, reason=*}` — lifecycle event counter
- `gate_flow_buffer_ack_trimmed_total`
- `gate_flow_buffer_overflow_total{policy}`
- `gate_flow_buffer_dropped_total{policy}`
- `gate_flow_buffer_ack_lag_ms`
- `gate_flow_active` / `gate_flow_attached` Gauges
- `gate_flow_offline_*` series (B3 metrics)
- `gate_flow_cross_evict_published_total` / `gate_flow_cross_evict_received_total` / `gate_flow_cross_evict_invalid_total` (B2 Pub/Sub channel metrics)
- `gate.flow.event` logger content for all lifecycle events (including `event=flow.resumed crossTakeover=true ...`)

#### Scenario: lifecycle counters still increment

- **GIVEN** the change is deployed
- **WHEN** a successful RESUME completes
- **THEN** `gate_flow_total{event="resumed", reason="ok"}` SHALL still increment by 1 (in addition to the new `gate_flow_resume_total{kind, outcome}`)

#### Scenario: destroyed lifecycle counter survives

- **GIVEN** a cross-instance takeover that evicts a local flow
- **WHEN** the local destroy completes
- **THEN** `gate_flow_total{event="destroyed", reason="cross_takeover"}` SHALL still increment by 1

#### Scenario: B2 evict channel counters survive

- **GIVEN** a cross-instance takeover with successful publish
- **WHEN** the publish completes
- **THEN** `gate_flow_cross_evict_published_total` SHALL still increment by 1 (this is the Pub/Sub channel-level counter, orthogonal to `gate_flow_takeover_total{result="evicted_remote"}` which is the takeover-result-level view)

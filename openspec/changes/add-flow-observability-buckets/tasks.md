## 1. 配置

- [x] 1.1 `GateConfig.FlowConfig` 新增 `ObservabilityConfig` 子段（8 字段：enabled / takeover-total-enabled / resume-total-enabled / replay-total-enabled / latency-enabled / cross-logger-enabled / histogram-slo / resume-timeout-ms）
- [x] 1.2 `application.yml` 暴露 `gate.flow.observability.*` 并加 `GATE_FLOW_OBSERVABILITY_*` 环境变量绑定
- [x] 1.3 启动日志：在 `FlowSessionManager.init()` 末尾打印 `event=flow.observability.summary enabled=… takeoverTotal=… resumeTotal=… replayTotal=… latency=… crossLogger=… slo=[…] timeoutMs=…`

## 2. FlowMetrics 常量类（新增）

- [x] 2.1 新建包 `com.clawai.gatedemo.gate.flow.metrics`
- [x] 2.2 `FlowMetrics` 类：构造接 `MeterRegistry` + `ObservabilityConfig`；持有 `ConcurrentHashMap<TagsKey, Meter>` 句柄缓存
- [x] 2.3 静态常量：5 个指标名 + 4 个标签名 + 全部合法标签值（用作枚举校验 + 单测穷举）
- [x] 2.4 `Counter takeover(String result)` / `Counter resumeTotal(String kind, String outcome)` / `Counter replay(String source, String outcome)`
- [x] 2.5 `Timer resumeLatency(String kind)` / `Timer crossReadyLatency()`，按 SLO bucket 构造 `Timer.builder(...).publishPercentileHistogram().serviceLevelObjectives(...)`
- [x] 2.6 静态 `String mapTakeoverResult(String rawReason)`（含 `OWNER_SAME/CHANGED→succeeded` 等映射 + 未知值 fallback `rejected`）
- [x] 2.7 静态 `String mapResumeOutcome(RejectReason)`（EXPIRED→rejected_expired 等）
- [x] 2.8 per-metric switch：每个 `takeover/resumeTotal/replay/latency` 方法在对应 sub-switch=false 时返回 noop（不在 hot path 加 if）
- [x] 2.9 `FlowMetrics.noop()` 工厂：返回无副作用实现（用于 `meterRegistry==null` 或 master=false 测试）

## 3. FlowSessionManager 集成 + 旧指标下线

- [x] 3.1 注入 `FlowMetrics flowMetrics`；构造时若 `meterRegistry==null` 用 `FlowMetrics.noop()`
- [x] 3.2 **删除** `resumeLatencyTimer` 字段与 `init()` 中旧 Timer 注册；改由 `flowMetrics.resumeLatency(kind).record(...)`
- [x] 3.3 **删除** `emitCounter("cross_takeover", reason)` 全部调用（5 处：行 302/307/311/313）；改为 `flowMetrics.takeover(FlowMetrics.mapTakeoverResult(rawReason)).increment()`
- [x] 3.4 `newFlow(...)` 成功后 emit `gate_flow_resume_total{kind=new, outcome=succeeded}` + `gate_flow_latency_resume_ms{kind=new}`
- [x] 3.5 `resume(...)` 成功路径：根据 `crossTookOver` emit `gate_flow_resume_total{kind=same_gw|cross_gw, outcome=succeeded}` + `gate_flow_latency_resume_ms{kind}` record
- [x] 3.6 `resume(...)` 拒绝路径：根据 `rejectReason` emit `outcome=rejected_expired|rejected_mismatch|rejected_owner_other`
- [x] 3.7 `resume(...)` 跨实例分支：`mapTakeoverResult(reason)` 后 emit `gate_flow_takeover_total{result=...}`
- [x] 3.8 `resume(...)` 跨实例成功 emit `gate_flow_latency_cross_ready_ms`（一次性，含 buffer + offline merge 总时长）
- [x] 3.9 `resume(...)` 跨实例成功末尾 emit `gate_flow_replay_total{source=merged, outcome=replayed}` count=buffer.replayed+offline.replayed
- [x] 3.10 `resume(...)` try/catch：channel 已 inactive 时 emit `gate_flow_resume_total{kind=..., outcome=cancelled}`
- [x] 3.11 `resume(...)` finally：时长 > `resume-timeout-ms` 时 emit `gate_flow_resume_total{kind=..., outcome=timeout}`（与 succeeded 互斥）
- [x] 3.12 新增 package-friendly `void recordReplay(String source, String outcome, int count)`，供 `OfflineMessageService` 调用
- [x] 3.13 `replayPending` 改为：trimmed 帧调 `recordReplay("buffer","skipped_acked",trimmed)`；实际写入帧调 `recordReplay("buffer","replayed",written)`；channel inactive 时 `recordReplay("buffer","dropped",remaining)`
- [x] 3.14 **删除** `gate_flow_buffer_replay_total` 计数（搜索 `gate_flow_buffer_replay_total` 在 `FlowSessionManager.java` 中的全部 `.increment()` 调用）
- [x] 3.15 新增 `crossLogger = LoggerFactory.getLogger("gate.cross.event")`；按 §5 细分写日志

## 4. OfflineMessageService 集成

- [x] 4.1 持有 `FlowSessionManager`（已注入，B3 已就绪）；不直接持有 `MeterRegistry`
- [x] 4.2 `replayAndMerge` 过滤掉的条目 → `manager.recordReplay("offline","skipped_acked",skipped)`
- [x] 4.3 `replayAndMerge` 实际写入条目 → `manager.recordReplay("offline","replayed",replayed)`
- [x] 4.4 `replayAndMerge` 失败 / channel inactive → `manager.recordReplay("offline","dropped",remaining)`
- [x] 4.5 检查并 **删除** 任何直接对 `gate_flow_buffer_replay_total` 的引用（应为零）

## 5. logger 拆分（gate.cross.event 顶层独立）

- [x] 5.1 `FlowSessionManager.resume(...)` 成功且 `crossTookOver==true` 时，emit `event=cross.takeover.result flowId=... result=succeeded ageMs=... bufferReplayed=N offlineReplayed=M` 到 `gate.cross.event`（受 `cross-logger-enabled` 开关控制）
- [x] 5.2 `FlowSessionManager.evictByCrossInstanceTakeover` emit `event=cross.evict.received flowId=... newOwnerGateId=... localOwner=...` 到 `gate.cross.event`
- [x] 5.3 `FlowEvictPublisher.publish` emit `event=cross.takeover.publish flowId=... previousOwnerGateId=... newOwnerGateId=...` 到 `gate.cross.event` + `flowMetrics.takeover("evicted_remote").increment()`
- [x] 5.4 `FlowEvictListener` 收入站后 emit `flowMetrics.takeover("evicted_by_remote").increment()` + crossLogger 记录
- [x] 5.5 `gate.flow.event` 中原 `event=flow.resumed crossTakeover=true ...` **保持不变**（lifecycle 日志一致）

## 6. 旧指标下线 — code-level cleanup

- [x] 6.1 全局 grep `gate_flow_buffer_replay_total` → 0 hit（除 docs / archived spec）
- [x] 6.2 全局 grep `gate_flow_resume_latency_ms` → 0 hit（除 docs / archived spec）
- [x] 6.3 全局 grep `emitCounter\("cross_takeover"` → 0 hit
- [x] 6.4 保留：`gate_flow_total{event=new|resumed|destroyed|detached|buffer_dropped|buffer_overflow, reason=*}` 全部 emit 不动
- [x] 6.5 保留：`gate_flow_buffer_ack_trimmed_total` / `gate_flow_buffer_overflow_total` / `gate_flow_buffer_dropped_total` / `gate_flow_buffer_ack_lag_ms` 全部不动
- [x] 6.6 保留：`gate_flow_active` / `gate_flow_attached` / `gate_flow_offline_*` / `gate_flow_cross_evict_*` 全部不动

## 7. 测试

- [x] 7.1 新增 `FlowMetricsTest`：mapping 表穷举 + 句柄缓存命中 + noop 安全 + SLO bucket 配置（≥ 8 用例）
- [x] 7.2 扩展 `FlowSessionManagerTest`：
  - [x] 7.2.1 `resume_sameGw_emitsResumeTotalAndLatency_kindSameGw`
  - [x] 7.2.2 `resume_crossGw_emitsResumeTotalAndBothTimers_kindCrossGw`
  - [x] 7.2.3 `resume_rejectedExpired_emitsOutcomeRejectedExpired`
  - [x] 7.2.4 `resume_rejectedMismatch_emitsOutcomeRejectedMismatch`
  - [x] 7.2.5 `resume_cancelledOnChannelClose_emitsOutcomeCancelled`
  - [x] 7.2.6 `resume_exceededTimeoutMs_emitsOutcomeTimeout`
  - [x] 7.2.7 `takeover_ownerSame_emitsResultSucceeded`
  - [x] 7.2.8 `takeover_ownerChanged_emitsResultSucceededAndEvictedRemote`
  - [x] 7.2.9 `takeover_expired_emitsResultMetadataMissing`
  - [x] 7.2.10 `takeover_redisUnavailable_emitsResultRedisUnavailable`
  - [x] 7.2.11 `newFlow_emitsKindNewOutcomeSucceeded`
  - [x] 7.2.12 `replayPending_emitsBufferReplayedAndSkipped`
  - [x] 7.2.13 `legacyMetricsNotEmitted` —— 验证 `gate_flow_total{event="cross_takeover"}` / `gate_flow_buffer_replay_total` / `gate_flow_resume_latency_ms` **不在** registry 中
- [x] 7.3 扩展 `OfflineMessageServiceTest`：
  - [x] 7.3.1 `replayAndMerge_emitsOfflineReplayedAndSkippedViaManager`
  - [x] 7.3.2 `replayAndMerge_crossGwResume_alsoEmitsMergedSource`
- [x] 7.4 扩展 `FlowEvictListenerTest`：`onMessage_emitsResultEvictedByRemoteAndCrossLogger`
- [x] 7.5 新增 `FlowEvictPublisherTest`（如未存在）：`publish_emitsResultEvictedRemoteAndCrossLogger`
- [x] 7.6 配置开关：
  - [x] 7.6.1 `observability_disabled_disablesAllNewMetrics`（master=false）
  - [x] 7.6.2 `takeoverTotalDisabled_otherMetricsStillWork`（per-metric=false）
  - [x] 7.6.3 `latencyDisabled_timersNotRegistered`
- [x] 7.7 lifecycle 兼容：
  - [x] 7.7.1 `lifecycleCountersSurvive` —— resume 后 `gate_flow_total{event=resumed,reason=ok}` 仍 +1
  - [x] 7.7.2 `destroyedReasonCrossTakeoverSurvives` —— B2 evict 后 `gate_flow_total{event=destroyed,reason=cross_takeover}` 仍 +1

## 8. 文档

- [x] 8.1 新增 `docs/observability-flow-metrics.md`：
  - [x] 8.1.1 完整 metric 字典（5 个新 metric × 全部标签值）
  - [x] 8.1.2 Migration 表：旧 metric → 新 metric 名 + PromQL 改写示例
  - [x] 8.1.3 推荐 PromQL 表达式（cross_takeover 成功率 / kind=cross_gw 占比 / p95 latency by kind / replay outcome breakdown / evicted_remote vs evicted_by_remote Pub/Sub 对账）
  - [x] 8.1.4 注意事项：`evicted_remote` 与 `succeeded` 重复计数的查询方式
- [x] 8.2 更新 `docs/flow-session-resume.md` 末尾追加「Observability」小节，指向新文档
- [x] 8.3 更新 `docs/ai-sessions/2026-05-18-gatedemo-phase-b-multi-transport-and-final-wrapup.md`（如适用）追加本 change 实施后续

## 9. OpenSpec archive 阶段 cleanup（人工提醒）

> 当 `gate-resume-reconnect` / `gate-flow-downstream-buffer` / `gate-flow-cross-instance` 这 3 个 capability 通过 `openspec archive` 落到 `openspec/specs/` 后，需要人工同步修订 archived spec 中关于旧 metric 的 Requirement 文字。**本提案 tasks 不直接修改这些 spec**（OpenSpec MODIFIED workflow 要求 source 已 archived）。

- [ ] 9.1 archive `add-netun-resume-flow-session` 后，编辑 `openspec/specs/gate-resume-reconnect/spec.md` —— `### Requirement: 可观测性最低要求` 中 `gate_flow_resume_latency_ms` 改为 `gate_flow_latency_resume_ms{kind=*}`
- [ ] 9.2 archive `add-flow-downstream-buffer` 后，编辑 `openspec/specs/gate-flow-downstream-buffer/spec.md`：
  - [ ] 9.2.1 `### Requirement: RESUME 成功后从 buffer 重放` 中 Scenario 「完整重放」`gate_flow_buffer_replay_total SHALL +20` 改为 `gate_flow_replay_total{source="buffer", outcome="replayed"} SHALL +20`
  - [ ] 9.2.2 同 Requirement 中 Scenario 「客户端已最新」`gate_flow_buffer_replay_total{count=0}` 改为 `gate_flow_replay_total{source="buffer", outcome="replayed"}`
  - [ ] 9.2.3 `### Requirement: buffer 可观测性` 中 metric list `gate_flow_buffer_replay_total` 替换为 `gate_flow_replay_total{source=buffer, outcome=*}`
  - [ ] 9.2.4 同 Requirement 中 Scenario 「重放计数器」对应替换
- [ ] 9.3 archive `add-cross-instance-flow-takeover` 后，编辑 `openspec/specs/gate-flow-cross-instance/spec.md`：
  - [ ] 9.3.1 `### Requirement: 跨实例迁移可观测性` 中 `gate_flow_cross_takeover_total{outcome}` 改为 `gate_flow_takeover_total{result}` + 6 值枚举说明
  - [ ] 9.3.2 同 Requirement 中 Scenario 「跨实例 RESUME 触发完整观测链」`{outcome=owner_changed}` 改为 `{result=succeeded}` + 增加 `{result=evicted_remote} +1` 与 `{result=evicted_by_remote} +1`
  - [ ] 9.3.3 `### Requirement: 跨实例 RESUME 后 buffer 冷启动语义` 中 `gate_flow_buffer_replay_total SHALL 不被本次 RESUME 增加` 改为 `gate_flow_replay_total{source="buffer"} SHALL 不被本次 RESUME 增加`
- [ ] 9.4 （可选）如团队希望严格 spec-level conformance，可在所有 archive 完成后追加一个 `refine-flow-observability-cleanup` change，用标准 OpenSpec MODIFIED workflow 严格修订（本提案 tasks 范围外）

## 10. OpenSpec 校验

- [x] 10.1 `openspec validate add-flow-observability-buckets --strict`

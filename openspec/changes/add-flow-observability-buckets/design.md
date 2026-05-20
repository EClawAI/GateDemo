# Design: 观测性补齐 + 命名统一 + 旧指标下线

## 1. 背景与目标

### 1.1 已识别的差距 + 命名 / cardinality 问题

| 缺口 | 现状 | gateway2 / 业界做法 |
|------|------|-------------------|
| 跨实例 takeover 无业务语义分桶 | `gate_flow_total{event=cross_takeover, reason=底层枚举}` | `cross_resume_takeover_total{result=succeeded/...}` |
| takeover 独立指标 spec 与代码偏差 | spec 要求 `gate_flow_cross_takeover_total{outcome}` 但代码未实现 | 本提案统一 |
| RESUME 延迟无 kind 分布 | `gate_flow_resume_latency_ms` 单 Timer | `cross_resume_ready_latency_ms` 独立 Histogram + by kind |
| RESUME 计数无 kind | `gate_flow_total{event=resumed, reason=ok}` | `resume_total{kind=same_gw/cross_gw}` |
| 重放无 source/outcome 拆分 | `gate_flow_buffer_replay_total` 单 Counter | `dedup_outcome_total{phase, outcome}` |
| 跨实例日志混杂 | 全部进 `gate.flow.event` | 独立 `cross-gw-resume` logger |
| 指标命名前缀不统一 | 名词 / 动词顺序混乱 | 业界惯例 `<system>_<noun>_<verb>` 一致 |
| cardinality 长期包袱 | 旧 metric 与新 metric 双写会无限增长 | 立即下线被替代项 |

### 1.2 设计目标

| 目标 | 衡量 |
|------|------|
| 命名统一为 `gate_flow_<noun>_<verb>` | 5 个新指标全部符合规则；旧不符合的下线 |
| 业务可读：result/outcome 标签具备业务语义 | SRE 直接 `sum by (result) (rate(...))` 出可读饼图 |
| 立即下线被替代指标，避免长期 cardinality 包袱 | 3 个旧 metric 在本变更后**不再 emit**，新告警必须迁移 |
| 实施风险低 | 仅在 `FlowSessionManager` 增量插入；不改业务方法签名；新增 metric 集中在 `FlowMetrics` 常量类 |
| 跨实例日志可独立路由 | 新 logger `gate.cross.event` 顶层独立，logback / ELK 可分别路由 |
| 测试可断言 | 用 `SimpleMeterRegistry.find(name).tag(...).counter().count()` 直接断言 mapping 正确 |
| per-metric 开关支持生产精细压量 | 6 个 sub-switch + 1 个 master，任一关闭即零开销 |

### 1.3 非目标（明确不做）

- 不改业务路径（RESUME / cross-takeover / offline merge 行为不变）；
- 不引入 OpenTelemetry / 链路追踪（属另一 epic）；
- 不做 Grafana 面板 JSON（仅在 docs 中列推荐表达式 + Migration 表）；
- 不动客户端协议、不动 Redis schema；
- **不保留**旧 metric（不做 alias / parallel emit）；
- 不在本变更内做严格 OpenSpec MODIFIED workflow（详见 §6.2）。

## 2. 整体设计

### 2.1 新增指标全集（精简命名）

```
┌─ Counters ───────────────────────────────────────────────────────────────┐
│ gate_flow_takeover_total{result}                                         │
│   result ∈ {succeeded, metadata_missing, redis_unavailable, rejected,    │
│             evicted_by_remote, evicted_remote}                           │
│                                                                          │
│ gate_flow_resume_total{kind, outcome}                                    │
│   kind    ∈ {same_gw, cross_gw, new}                                     │
│   outcome ∈ {succeeded, rejected_expired, rejected_mismatch,             │
│              rejected_owner_other, degraded_to_new,                      │
│              cancelled, timeout}                                         │
│                                                                          │
│ gate_flow_replay_total{source, outcome}                                  │
│   source  ∈ {buffer, offline, merged}                                    │
│   outcome ∈ {replayed, skipped_acked, dropped}                           │
└──────────────────────────────────────────────────────────────────────────┘

┌─ Timers (Histogram with SLO buckets) ────────────────────────────────────┐
│ gate_flow_latency_resume_ms{kind}                                        │
│ gate_flow_latency_cross_ready_ms     (no tag, dedicated to cross-gw)     │
│   buckets: 10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s             │
└──────────────────────────────────────────────────────────────────────────┘
```

### 2.2 result 标签 6 值 — 双向视角与去重指引

`FlowMetrics.mapTakeoverResult(reason)` 集中维护底层 → 业务 mapping：

| 底层 reason / 触发点 | result | emit 方 | 备注 |
|---|---|---|---|
| Lua `OWNER_SAME` | `succeeded` | 发起方 | 同实例自接管（事实上未发生真正跨实例迁移） |
| Lua `OWNER_CHANGED` | `succeeded` | 发起方 | 真正跨实例接管成功 |
| Lua `EXPIRED` | `metadata_missing` | 发起方 | flow 在 Redis 已过期 |
| Lua `REDIS_UNAVAILABLE` | `redis_unavailable` | 发起方 | Redis 故障 / 脚本异常 |
| 业务侧拒绝（未来扩展） | `rejected` | 发起方 | 预留：黑名单 / Lock-out |
| `FlowEvictListener` 收到 Pub/Sub 入站 | `evicted_by_remote` | 被驱逐方 | 本地 channel 被关闭 |
| `FlowEvictPublisher.publish` 发出 evict | `evicted_remote` | 发起方 | 与 `succeeded` 同动作，**额外子视角** |

#### 关键 Trade-off：`succeeded` 与 `evicted_remote` 的关系

- 一次跨 gw takeover 在发起方实例会 emit 两条：
  1. `gate_flow_takeover_total{result="succeeded"} +1`
  2. `gate_flow_takeover_total{result="evicted_remote"} +1`
- 简单 `sum(rate(gate_flow_takeover_total[5m]))` 会**重复计数**。

**Grafana 推荐查询**（避免重复）：
```promql
# 跨实例 takeover 总数（业务正确）
sum by (result) (rate(gate_flow_takeover_total{result!="evicted_remote"}[5m]))

# Pub/Sub 出入对账（验证可达性）
sum(rate(gate_flow_takeover_total{result="evicted_remote"}[5m]))      # 出
sum(rate(gate_flow_takeover_total{result="evicted_by_remote"}[5m]))   # 入
# 健康集群两者应近似相等（除去抖动）
```

**替代方案 A**（备选，design 推荐方案 B）：完全去掉 `evicted_remote`，让 Pub/Sub 出入对账走 `gate_flow_publish_count` 之类的独立 counter。本提案选 B（保留 6 值），原因：用户决策明确选 `rename-evicted`，且独立 counter 会增加更多 metric 名。

### 2.3 outcome 标签 7 值 — 增 cancelled / timeout

| outcome | 触发判定 | 实施位置 |
|---|---|---|
| `succeeded` | RESUME 全流程完成（含 offline merge） | `resume(...)` 末尾 |
| `rejected_expired` | `RejectReason.EXPIRED` | `resume(...)` 早退 |
| `rejected_mismatch` | `RejectReason.MISMATCH` | `resume(...)` 早退 |
| `rejected_owner_other` | `RejectReason.OWNER_OTHER` 且 cross-takeover disabled | `resume(...)` 早退 |
| `degraded_to_new` | 客户端带 flowId 但服务端走 NEW（如 metadata_missing） | `resume(...)` 降级路径 |
| `cancelled` | RESUME 进行中 channel 关闭（client disconnect） | `resume(...)` try/catch + channel state |
| `timeout` | 总耗时 > `resume-timeout-ms`（默认 3000ms） | `resume(...)` finally + 时长比较 |

`cancelled` / `timeout` 是诊断字段：实际计数会很少（只在弱网 / 资源耗尽时出现），但出现时极有价值。

### 2.4 kind 标签判定

在 `FlowSessionManager.resume()` 现有路径中 `crossTookOver` 局部变量直接对应 `kind=cross_gw`，否则 `kind=same_gw`。`NEW` 路径在 `newFlow()` 中以 `kind=new, outcome=succeeded` 写入 `gate_flow_resume_total` 实现「全口径计数」（含 NEW 登录）。

### 2.5 replay outcome + source=merged

| 调用点 | source | outcome | 触发条件 |
|---|---|---|---|
| `DownstreamBuffer.ackUpTo` 删除的帧 | `buffer` | `skipped_acked` | 因 lastClientRecvSeq 跳过 |
| `replayPending`（B1 buffer）写入 | `buffer` | `replayed` | 实际写入 channel 的帧数 |
| `replayPending` channel inactive | `buffer` | `dropped` | flush 时 channel 不再可写 |
| `OfflineMessageService.replayAndMerge` 过滤掉 | `offline` | `skipped_acked` | `gwSeq ≤ fromSeq` |
| `OfflineMessageService.replayAndMerge` 写入 | `offline` | `replayed` | XRANGE 后写 channel |
| `OfflineMessageService.replayAndMerge` 写失败 | `offline` | `dropped` | XDEL 失败 / channel inactive |
| `resume()` 跨实例成功末尾 | `merged` | `replayed` | totalReplayed = buffer + offline，一次性 emit |

`source=merged` 的 outcome 仅会是 `replayed`（合并视角下不区分 skip/drop，那些已由 buffer/offline 各自细分）。

由 `FlowSessionManager.recordReplay(source, outcome, count)` 暴露的 package-friendly 方法集中写入，避免 `OfflineMessageService` 直接持有 `MeterRegistry` 双份引用。

### 2.6 logger `gate.cross.event` 顶层独立

```
gate.flow.event   ─── flow 生命周期（new / resumed / detached / destroyed / buffer / offline）
gate.cross.event  ─── 跨实例事件（cross-takeover 入/出、evict 发布、evict 订阅）
                       注：顶层 cross.* 与 flow.* 并列，方便 ELK / logback appender 拆开
```

- 两者都用 SLF4J + logback；
- 不强制要求 logback.xml 拆 appender，但**支持**用户后续拆（key=value 结构化已经在位）；
- 现有 `emitEvent("flow.resumed", ...)` 若 `crossTookOver==true`，**同时**写一份精简事件到 `gate.cross.event`（含 `previousOwnerGateId` / `bufferReplayed` / `offlineReplayed`）；
- 现有 `event=flow.resumed crossTakeover=true ...` 在 `gate.flow.event` 中**保留不动**（保持 lifecycle 日志一致）。

### 2.7 配置开关（per-metric）

```yaml
gate:
  flow:
    observability:
      enabled: true                       # master switch；false 时全部关闭
      takeover-total-enabled: true
      resume-total-enabled: true
      replay-total-enabled: true
      latency-enabled: true               # 控制两个 Timer
      cross-logger-enabled: true          # gate.cross.event
      histogram-slo: "10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s"
      resume-timeout-ms: 3000             # outcome=timeout 触发阈值
```

`enabled=false` 时所有 sub-switch 无效，整体走 noop；`enabled=true` 时按各 sub-switch 独立判断 Meter 是否注册。

### 2.8 旧指标下线（立即生效）

| 旧指标 | 处理 |
|---|---|
| `gate_flow_total{event=cross_takeover, reason=*}` | **代码层完全移除 emit**；替代 `gate_flow_takeover_total{result=*}` |
| `gate_flow_buffer_replay_total` | **代码层完全移除 emit**；替代 `gate_flow_replay_total{source=buffer, outcome=replayed}` |
| `gate_flow_resume_latency_ms`（旧无 tag Timer） | **代码层完全移除注册**；替代 `gate_flow_latency_resume_ms{kind=*}` |

**保留**（语义不同，非被替代）：
- `gate_flow_total{event=new|resumed|destroyed|detached|buffer_dropped|buffer_overflow, reason=*}` — lifecycle 事件计数
- `gate_flow_buffer_ack_trimmed_total`
- `gate_flow_buffer_overflow_total{policy}`
- `gate_flow_buffer_dropped_total{policy}`
- `gate_flow_buffer_ack_lag_ms`
- `gate_flow_active` / `gate_flow_attached` Gauges
- `gate_flow_offline_*` 全部
- `gate_flow_cross_evict_published_total` / `gate_flow_cross_evict_received_total` / `gate_flow_cross_evict_invalid_total`（这些是 evict pub/sub 通道指标，与 takeover 结果是不同维度）

## 3. 实施切片

### 3.1 新增类

`com.clawai.gatedemo.gate.flow.metrics.FlowMetrics`：

- 持有 `MeterRegistry` + `ObservabilityConfig`；
- 静态常量保存指标名 / 标签名 / 所有合法标签值（用作枚举校验）；
- API：
  - `Counter takeover(String result)`
  - `Counter resumeTotal(String kind, String outcome)`
  - `Counter replay(String source, String outcome)`
  - `Timer resumeLatency(String kind)`
  - `Timer crossReadyLatency()`
- 内部 `ConcurrentHashMap<TagsKey, Meter>` 缓存，避免高频路径重复 lookup；
- 静态映射：
  - `String mapTakeoverResult(String rawReason)` —— `OWNER_SAME/OWNER_CHANGED→succeeded`, `EXPIRED→metadata_missing`, `REDIS_UNAVAILABLE→redis_unavailable`, default→`rejected`
  - `String mapResumeOutcome(RejectReason)` —— `EXPIRED→rejected_expired`, `MISMATCH→rejected_mismatch`, `OWNER_OTHER→rejected_owner_other`
- `noop()` 工厂：返回无副作用实现（用于 `meterRegistry==null` 或 sub-switch=false 测试）；
- per-metric switch：`takeover()` 等方法在 sub-switch=false 时直接返回 noop counter（不在 hot path 加 if）。

### 3.2 改动文件

| 文件 | 改动 |
|---|---|
| `FlowSessionManager.java` | 注入 `FlowMetrics`；**删除** `resumeLatencyTimer` 字段与 `init()` 中旧 Timer 注册；**删除** `emitCounter("cross_takeover", ...)` 调用；`resume()` 在 success/failure 路径打新 counter + record latency by kind；新增 `recordReplay(source, outcome, count)` package-friendly 方法；`replayPending` 改为 fine-grained outcome emit；新增 `crossLogger` 字段 + 写日志；新增 `cancelled`/`timeout` 判定（try/catch + finally 时长比较） |
| `OfflineMessageService.java` | `replayAndMerge` / `flushBufferToOffline` 调 `manager.recordReplay(...)`；**删除** 任何对 `gate_flow_buffer_replay_total` 的直接引用（若有） |
| `FlowEvictListener.java` | 收到入站 evict 后调 `flowMetrics.takeover("evicted_by_remote").increment()` + 写 `gate.cross.event` |
| `FlowEvictPublisher.java` | publish 后调 `flowMetrics.takeover("evicted_remote").increment()` + 写 `gate.cross.event` |
| `GateConfig.FlowConfig` | 新增 `ObservabilityConfig` 子段（8 个字段） |
| `application.yml` | 暴露 `gate.flow.observability.*` |

### 3.3 不改动

- `FlowSession.java`、`DownstreamBuffer.java`、`RedisFlowStore.java`、协议 / proto、`PlayerService`、`GateNettyWebSocketHandler`、Phase B4 transport 抽象。

## 4. 测试策略

### 4.1 单元

- `FlowMetricsTest`：
  - `mapTakeoverResult` 5 个枚举值穷举映射；
  - `mapResumeOutcome` 各 RejectReason 映射；
  - per-metric switch 关闭时返回 noop（不污染 registry）；
  - Counter / Timer 句柄缓存（同 tag 多次调用复用同一 Meter）；
  - `noop()` 工厂安全。

- 扩展 `FlowSessionManagerTest`：
  - `resume_sameGw_emitsResumeTotalAndLatency_kindSameGw`
  - `resume_crossGw_emitsResumeTotalAndBothTimers_kindCrossGw`
  - `resume_rejectedExpired_emitsOutcomeRejectedExpired`
  - `resume_rejectedMismatch_emitsOutcomeRejectedMismatch`
  - `resume_cancelledOnChannelClose_emitsOutcomeCancelled`
  - `resume_exceededTimeoutMs_emitsOutcomeTimeout`
  - `takeover_ownerSame_emitsResultSucceeded`
  - `takeover_ownerChanged_emitsResultSucceeded_andEvictedRemote`（发起方双 emit）
  - `takeover_expired_emitsResultMetadataMissing`
  - `takeover_redisUnavailable_emitsResultRedisUnavailable`
  - `newFlow_emitsKindNewOutcomeSucceeded`
  - `replayPending_emitsBufferReplayedAndSkipped`
  - `legacyMetrics_notEmitted` —— 验证旧 `gate_flow_total{event=cross_takeover}` / `gate_flow_buffer_replay_total` / `gate_flow_resume_latency_ms` **不再** 被注册

- 扩展 `OfflineMessageServiceTest`：
  - `replayAndMerge_emitsOfflineReplayedAndSkippedViaManager`
  - `replayAndMerge_crossGwResume_alsoEmitsMergedSource`

- 扩展 `FlowEvictListenerTest`：
  - `onMessage_emitsResultEvictedByRemoteAndCrossLogger`

- 新增 `FlowEvictPublisherTest`（如未存在则新建）：
  - `publish_emitsResultEvictedRemoteAndCrossLogger`

- 配置开关：
  - `observability_disabled_disablesAllNewMetrics`（master switch）
  - `takeoverTotalDisabled_otherMetricsStillWork`（per-metric switch）

### 4.2 不引入新集成测试

观测性 change 不应改业务行为；现有 Phase B 集成测路径不需要改。

## 5. 风险与回滚

| 风险 | 缓解 |
|---|---|
| 旧 metric 立即下线破坏现有 Grafana / 告警 | 文档 §8 提供 Migration 表；建议先在测试环境验证再灰度 |
| 新指标 cardinality 过高 | 标签值全有限枚举：result(6) × — = 6；kind(3) × outcome(7) = 21；source(3) × outcome(3) = 9；总计 < 50 系列 |
| 双写指标拖慢 RESUME 路径 | Counter / Timer 写入是 ns 级；缓存 Meter 句柄后无热路径 lookup；per-metric switch=false 时是 noop 直返 |
| Logger 拆分破坏现有 ELK | `gate.cross.event` 默认走 root appender，与 `gate.flow.event` 同源；用户主动配置才会拆 |
| Histogram bucket 选错导致序列爆炸 | custom-fine 9 个 bucket × 21 标签组合 = 189 系列；可接受 |
| `cancelled` / `timeout` 判定误报 | 默认 timeout=3s，远大于正常 RESUME（< 500ms 即使 cross-gw）；可通过配置调高 |
| `evicted_remote` vs `succeeded` 重复计数 | docs 明确推荐查询；spec 中 result Requirement 明文标注 |

**回滚路径**：
1. **最轻**：`gate.flow.observability.enabled=false`，所有新 meter 不注册，新 logger 静默；旧 metric 仍然不 emit（因为已经下线）—— 此时 Grafana 完全没数据；
2. **中等**：上线本变更前先准备 Grafana 备用 dashboard（用新 metric 名），灰度切换；
3. **重**：回滚代码到本变更前 commit，旧 metric 自动恢复 emit。

## 6. OpenSpec 流程注意事项

### 6.1 当前限制

本提案影响 3 个 capability（`gate-resume-reconnect` / `gate-flow-downstream-buffer` / `gate-flow-cross-instance`）中关于 metric 的 Requirement 文字。OpenSpec MODIFIED Requirements workflow 严格要求 source spec 已 archived 到 `openspec/specs/`，但这 3 个 capability **当前仍在 `changes/`**（in-progress）。

### 6.2 选定方案：pragmatic 折中

- 本提案 `specs/gate-flow-observability/spec.md` **仅写 ADDED Requirements**；
- `proposal.md` 与 `tasks.md` 显式标注「下线旧 metric」与 cleanup 责任；
- **当上游 4 个 capability archive 时**（通过 `openspec archive`），人工同步修订 archived spec 中关于旧 metric 的 Requirement 文字（详见 tasks §9）；
- 如团队希望严格 spec-level conformance，可在 4 个 capability archive 后追加一个 `refine-flow-observability-cleanup` change，用标准 MODIFIED workflow 严格修订。

### 6.3 验证策略

- 本提案运行 `openspec validate add-flow-observability-buckets --strict` 应通过（仅 ADDED Requirements）；
- 代码层面通过 `legacyMetrics_notEmitted` 单测保证旧指标不再 emit；
- 4 个 capability 仍可独立 archive，archive 时旧 Requirement 文字与代码会出现「spec 写要求 emit、代码已不 emit」的暂时性不一致 —— 由 archive 阶段 cleanup 解决。

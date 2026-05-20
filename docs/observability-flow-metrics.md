# Flow Observability Metrics（add-flow-observability-buckets）

本文档由 [`openspec/changes/add-flow-observability-buckets/`](../openspec/changes/add-flow-observability-buckets) 落地，是 Phase B 之后 FlowSession / 跨实例 takeover 的官方观测性参考。

适用对象：
- SRE / Ops 在 Grafana 上看 Phase B 弱网 + 跨实例迁移健康度。
- 后续在 `FlowSessionManager` / `FlowEvictPublisher` / `FlowEvictListener` 等位置新增 emit 时的命名 / 标签规范。

---

## 1. 5 个新指标速查

| Metric                              | Type    | Tags                | 语义                                                           |
| ----------------------------------- | ------- | ------------------- | -------------------------------------------------------------- |
| `gate_flow_takeover_total`          | Counter | `result`            | 跨实例 takeover 出参的 6 类结果（含发起方 / 被驱逐方）。       |
| `gate_flow_resume_total`            | Counter | `kind`, `outcome`   | RESUME（与 NEW）按来源类别 × 结果类别的二维分桶。              |
| `gate_flow_replay_total`            | Counter | `source`, `outcome` | 下行帧重放出参（buffer / offline / 跨实例合并视图）。           |
| `gate_flow_latency_resume_ms`       | Timer   | `kind`              | RESUME (与 NEW) 端到端耗时（毫秒），带 SLO bucket。            |
| `gate_flow_latency_cross_ready_ms`  | Timer   | —                   | 跨实例 RESUME 的 ready latency（AUTH → 首帧可写入），带 SLO。 |

所有指标命名遵循 `gate_flow_<noun>_<verb>` 约定；Timer 名以 `_ms` 结尾仅作可读提示，实际单位由 Micrometer 决定（毫秒）。

---

## 2. 标签字典（合法值穷举）

### 2.1 `gate_flow_takeover_total{result}`（6 值）

| 值                  | 触发位置                              | 说明                                                 |
| ------------------- | ------------------------------------- | ---------------------------------------------------- |
| `succeeded`         | `FlowSessionManager.resume`（跨实例） | Lua `crossTakeover` 返回 `RESUMED_OWNER_SAME/CHANGED` |
| `metadata_missing`  | 同上                                  | Lua 返回 `EXPIRED`（owner 信息缺失，无法迁移）       |
| `redis_unavailable` | 同上                                  | Lua 返回 `REDIS_UNAVAILABLE`，本次降级为拒绝         |
| `rejected`          | fallback                              | 未来未知的拒绝原因，落到 fallback bucket             |
| `evicted_remote`    | `FlowEvictPublisher.publish`          | 本实例作为新 owner 主动向旧 owner 广播 evict 成功    |
| `evicted_by_remote` | `FlowEvictListener.onMessage`         | 本实例作为旧 owner 接到 evict，实际驱逐本地 flow 时计 |

> **重复计数说明**：一次跨实例迁移会同时让发起方 +1 `succeeded` 与 +1 `evicted_remote`，让被驱逐方 +1 `evicted_by_remote`。三类标签**互为独立视角**：
> - `succeeded` = 业务成功率分子
> - `evicted_remote` = 发起方 PubSub 发起健康度
> - `evicted_by_remote` = 全集群被动驱逐健康度（用于 Pub/Sub 对账）

### 2.2 `gate_flow_resume_total{kind, outcome}`

`kind` 3 值：

| 值         | 说明                                          |
| ---------- | --------------------------------------------- |
| `same_gw`  | RESUME 命中本地缓存或同实例 Redis owner       |
| `cross_gw` | RESUME 触发了跨实例 owner 迁移                 |
| `new`      | NEW 路径（首登 / 降级），不来自有效 flowId    |

`outcome` 7 值：

| 值                      | 说明                                                       |
| ----------------------- | ---------------------------------------------------------- |
| `succeeded`             | RESUME 成功换绑 / NEW 成功 mint                            |
| `rejected_expired`      | flowId / record 过期或缺失                                  |
| `rejected_mismatch`     | playerId 与 record 不一致                                   |
| `rejected_owner_other`  | 跨实例迁移被禁用或 Lua 不可用，按 Phase A 拒绝             |
| `degraded_to_new`       | RESUME 失败后降级为 NEW（与 `succeeded` 互斥）             |
| `cancelled`             | RESUME 期间 channel 已 inactive                            |
| `timeout`               | RESUME 总耗时 > `gate.flow.observability.resume-timeout-ms` |

### 2.3 `gate_flow_replay_total{source, outcome}`

| `source`   | 说明                                                |
| ---------- | --------------------------------------------------- |
| `buffer`   | 本地 `DownstreamBuffer` 重放（`FlowSessionManager.replayPending`） |
| `offline`  | Redis Stream 离线消息重放（`OfflineMessageService.replayAndMerge`） |
| `merged`   | 跨实例 RESUME 末尾的合并汇总视图（数量 = buffer + offline）      |

| `outcome`        | 触发条件                                                          |
| ---------------- | ----------------------------------------------------------------- |
| `replayed`       | 帧成功写入新 channel                                              |
| `skipped_acked`  | 客户端已 ACK，trim 或上层 dup 过滤跳过                            |
| `dropped`        | 写入前 channel 已 inactive，被丢弃                                |

### 2.4 `gate_flow_latency_resume_ms{kind}` & `gate_flow_latency_cross_ready_ms`

SLO bucket 默认：`10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s`，通过 `gate.flow.observability.histogram-slo` 配置覆盖。

---

## 3. 旧 → 新 Migration 表

> 三项旧 metric 在本变更落地后**立刻停止发送**（代码层面已删 emit 调用，验证：`grep` 已 0 hit 业务代码）。

| 旧 metric                                                                    | 新 metric                                                                     | 注意                                       |
| ---------------------------------------------------------------------------- | ----------------------------------------------------------------------------- | ------------------------------------------ |
| `gate_flow_total{event="cross_takeover", reason=*}`                          | `gate_flow_takeover_total{result=*}`                                          | reason `owner_same`/`owner_changed` 合并为 `succeeded`；新增 `evicted_remote` / `evicted_by_remote` 两个独立视角；reason `expired` → `metadata_missing` |
| `gate_flow_buffer_replay_total` (Counter, 无 tag)                            | `gate_flow_replay_total{source="buffer", outcome="replayed"}`                 | 同时新增 `skipped_acked` / `dropped` outcome |
| `gate_flow_resume_latency_ms` (Timer, 无 tag)                                | `gate_flow_latency_resume_ms{kind=same_gw\|cross_gw\|new}`                    | 新增 `gate_flow_latency_cross_ready_ms` 专用于跨实例 ready 时长 |

**保留 metric**（本变更不动）：
- `gate_flow_total{event=new|resumed|destroyed|detached|buffer_dropped|buffer_overflow, reason=*}` —— lifecycle 计数。
- `gate_flow_buffer_ack_trimmed_total` / `gate_flow_buffer_overflow_total` / `gate_flow_buffer_dropped_total` / `gate_flow_buffer_ack_lag_ms` —— B1 buffer 维度。
- `gate_flow_active` / `gate_flow_attached` —— gauge。
- `gate_flow_offline_*` —— B3 离线消息细粒度统计。
- `gate_flow_cross_evict_published_total` / `gate_flow_cross_evict_received_total` / `gate_flow_cross_evict_invalid_total` —— B2 PubSub 健康度。

---

## 4. 推荐 PromQL

```promql
# (a) 跨实例 takeover 成功率（5min 窗口）
sum(rate(gate_flow_takeover_total{result="succeeded"}[5m]))
/
sum(rate(gate_flow_takeover_total{result=~"succeeded|metadata_missing|redis_unavailable|rejected"}[5m]))

# (b) RESUME 总成功率（含 same_gw / cross_gw）
sum(rate(gate_flow_resume_total{kind=~"same_gw|cross_gw", outcome="succeeded"}[5m]))
/
sum(rate(gate_flow_resume_total{kind=~"same_gw|cross_gw"}[5m]))

# (c) 跨实例 RESUME 占比
sum(rate(gate_flow_resume_total{kind="cross_gw"}[5m]))
/
sum(rate(gate_flow_resume_total{kind=~"same_gw|cross_gw"}[5m]))

# (d) RESUME p95 latency by kind
histogram_quantile(0.95,
  sum by (le, kind) (rate(gate_flow_latency_resume_ms_bucket[5m]))
)

# (e) 跨实例 ready p95 latency
histogram_quantile(0.95,
  sum by (le) (rate(gate_flow_latency_cross_ready_ms_bucket[5m]))
)

# (f) replay outcome breakdown
sum by (source, outcome) (rate(gate_flow_replay_total[5m]))

# (g) Pub/Sub 对账：evicted_remote vs evicted_by_remote 应大体相等
sum(rate(gate_flow_takeover_total{result="evicted_remote"}[5m]))
-
sum(rate(gate_flow_takeover_total{result="evicted_by_remote"}[5m]))
# 持续 > 0 → 存在 evict 广播未被对端消费（网络分区或 listener 故障）

# (h) RESUME 超时占比
sum(rate(gate_flow_resume_total{outcome="timeout"}[5m]))
/
sum(rate(gate_flow_resume_total[5m]))
```

### 4.1 注意：`succeeded` 与 `evicted_remote` 的"双计数"

一次成功的跨实例 takeover 在发起方会同时让两个 counter +1：
- `gate_flow_takeover_total{result="succeeded"}` —— 业务成功率分子。
- `gate_flow_takeover_total{result="evicted_remote"}` —— PubSub 发起方健康度。

**不要**把分母写成 `sum(gate_flow_takeover_total)` 然后算成功率（会把 evicted_remote 也算到分母）。**正确**：用 (a) 中的 `result=~"succeeded|metadata_missing|redis_unavailable|rejected"` 显式枚举业务路径。

---

## 5. 配置（`gate.flow.observability.*`）

```yaml
gate:
  flow:
    observability:
      enabled: true                          # master kill switch
      takeover-total-enabled: true           # gate_flow_takeover_total
      resume-total-enabled: true             # gate_flow_resume_total
      replay-total-enabled: true             # gate_flow_replay_total
      latency-enabled: true                  # 两个 latency Timer
      cross-logger-enabled: true             # gate.cross.event logger
      histogram-slo: "10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s"
      resume-timeout-ms: 3000                # > 此阈值 emit outcome=timeout
```

环境变量绑定见 `application.yml`，前缀 `GATE_FLOW_OBSERVABILITY_*`。

**降级语义**：
- `enabled=false` → 所有 5 个新 metric 与 cross logger 全部 no-op（仍保留 B1/B2/B3 既有 metric）。
- 单个 `*-total-enabled=false` → 仅该 metric noop，其它正常工作。
- `latency-enabled=false` → 两个 Timer 都 noop。
- `cross-logger-enabled=false` → `gate.cross.event` 三类事件（`takeover.result` / `evict.received` / `takeover.publish`）不写，但 metric 不受影响。

---

## 6. 日志 channel 拆分

| Logger 名             | 顶层位置                                | 事件                                                    |
| --------------------- | --------------------------------------- | ------------------------------------------------------- |
| `gate.flow.event`     | 既有 lifecycle / buffer / offline 事件 | `flow.new` / `flow.resumed` / `flow.destroyed` / `flow.buffer.*` / `flow.offline.*` 等 |
| `gate.cross.event`    | **新增**：跨实例独立 channel          | `cross.takeover.result` / `cross.evict.received` / `cross.takeover.publish` |

`gate.cross.event` 与 `gate.flow.event` 平级（**不是** child logger），便于 logback `<logger name="gate.cross.event">` 单独配置 appender / level。在不修改 logback 的情况下，两个 logger 会被同一 root appender 处理。

---

## 7. 实施分布（源码 cross-reference）

| 位置                                                                                  | 职责                                                  |
| ------------------------------------------------------------------------------------- | ----------------------------------------------------- |
| `gate.flow.metrics.FlowMetrics`                                                       | 常量 + 句柄缓存 + per-metric switch + noop 工厂        |
| `FlowSessionManager.newFlow` / `newFlowAfterReject`                                   | kind=new 的 succeeded / degraded_to_new 互斥 emit     |
| `FlowSessionManager.resume`                                                           | kind=same_gw/cross_gw 的所有 outcome、cross_ready timer |
| `FlowSessionManager.replayPending`                                                    | source=buffer 三类 outcome                            |
| `FlowSessionManager.recordReplay`                                                     | package-friendly 入口，供 `OfflineMessageService` 调用 |
| `FlowSessionManager.evictByCrossInstanceTakeover`                                     | `gate.cross.event=cross.evict.received` 日志          |
| `OfflineMessageService.replayAndMerge`                                                | source=offline 的 replayed / skipped_acked emit       |
| `FlowEvictPublisher.publish`                                                          | `result=evicted_remote` + `cross.takeover.publish`     |
| `FlowEvictListener.onMessage`                                                         | `result=evicted_by_remote` + `cross.evict.received`    |

---

## 8. 后续清理项

`openspec/changes/add-flow-observability-buckets/tasks.md §9` 列出了一个延后清理列表：当 `gate-resume-reconnect` / `gate-flow-downstream-buffer` / `gate-flow-cross-instance` 三个 capability 通过 `openspec archive` 落到 `openspec/specs/` 后，archived spec 文本中关于旧 metric 的 Requirement 需手动同步修订为新名称。这是 OpenSpec 严格 `MODIFIED Requirements` workflow 限制带来的「pragmatic compromise」。

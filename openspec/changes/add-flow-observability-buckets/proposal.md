# Why

Phase A/B 已经覆盖会话语义层（RESUME / cross-instance takeover / 下行 buffer / 离线合流），但 **可观测性维度仍然偏粗**：

1. **跨实例 takeover 结果只有底层 reason**：现有 emit 走 `gate_flow_total{event=cross_takeover, reason=owner_same|owner_changed|expired|redis_unavailable}`，且 `gate-flow-cross-instance` spec 中要求的独立指标 `gate_flow_cross_takeover_total{outcome}` 实际**未实现**（代码 vs spec 偏差）。两者都没有 gateway2 风格的业务语义分桶（succeeded / metadata_missing / ...）；
2. **`gate_flow_resume_latency_ms` 是单一 Timer**：无 `kind` 维度，无法分别看「同实例 RESUME」与「跨实例 RESUME（含 Lua + Pub/Sub + offline merge）」，后者通常慢 1 个数量级，被平均掉就看不出问题；
3. **RESUME 结果无 kind 维度**：`emitCounter("resumed","ok")` 不知道是 same_gw 还是 cross_gw，无法计算「跨实例 RESUME 占比」（业务上判断 LB 黏性 / 弱网漂移的关键指标）；
4. **buffer / offline 重放无 outcome 拆分**：`gate_flow_buffer_replay_total` 只是总计，无法区分「实际重放」「因 ACK 已跳过」「因 channel 关闭丢弃」；缺 cross-instance RESUME 后的「merged 合计」语义；
5. **跨实例事件日志混在 `gate.flow.event`**：ELK 拆索引困难，gateway2 用独立的 `cross-gw-resume` logger 即可单独路由；
6. **指标命名前缀不统一**：现有 `gate_flow_*` 系列名词 / 动词顺序混乱（`gate_flow_buffer_replay_total` vs `gate_flow_cross_takeover_total` vs `gate_flow_resume_latency_ms`），不利于 Grafana 模糊查询。

本变更对齐 gateway2 的 `CrossResumeMetrics` / `UpstreamDedupMetrics` 思路，**不引入额外依赖**，仅在现有 Micrometer + SLF4J 之上重构 5 个指标 + 新增 1 个 logger，并**立即下线**已被替代的 3 个旧指标，避免长期 cardinality 包袱。

# What Changes

## 行为变化

### A. 命名统一为 `gate_flow_<noun>_<verb>`

新指标全部走精简命名（去掉冗余 `cross_` 前缀，把 `latency` 提到 noun 位）：

| 新指标名 | 类型 | 标签 | 含义 |
|----------|------|------|------|
| `gate_flow_takeover_total` | Counter | `result` | 6 值 result 枚举（详见下） |
| `gate_flow_resume_total` | Counter | `kind`, `outcome` | RESUME 全量计数；`kind` ∈ {`same_gw`, `cross_gw`, `new`}；`outcome` ∈ 7 值（详见下） |
| `gate_flow_latency_resume_ms` | Timer | `kind` | RESUME 端到端延迟，按 `kind` 分布 |
| `gate_flow_latency_cross_ready_ms` | Timer | — | 跨实例 RESUME 专用：AUTH 到 offline merge 完成、首帧可下发 |
| `gate_flow_replay_total` | Counter | `source`, `outcome` | `source` ∈ {`buffer`, `offline`, `merged`}；`outcome` ∈ {`replayed`, `skipped_acked`, `dropped`} |

### B. result 标签 6 值（gateway2-style + 双向 evict 视角）

| result | 触发场景 | emit 方 |
|--------|---------|---------|
| `succeeded` | `crossTakeover` 返回 `RESUMED_OWNER_SAME` 或 `RESUMED_OWNER_CHANGED` | 发起 takeover 的本实例 |
| `metadata_missing` | `crossTakeover` 返回 `EXPIRED` | 发起方 |
| `redis_unavailable` | `crossTakeover` 返回 `REDIS_UNAVAILABLE` | 发起方 |
| `rejected` | 未来业务侧拒绝（预留） | 发起方 |
| `evicted_by_remote` | 收到他人发起的 Pub/Sub evict | 被驱逐方（本地 channel 被关闭） |
| `evicted_remote` | 本实例发起 takeover 且 publish 了 evict | 发起方（额外子视角） |

> **重要**：`succeeded` 与 `evicted_remote` 是同一动作的两个视角（同一发起方 emit），相加会重复计数。Grafana 查询「跨实例 takeover 数」应直接用 `sum by (result) (rate(gate_flow_takeover_total{result="succeeded"}[5m]))`，**不要** sum `result!="evicted_by_remote"`。`evicted_remote` 仅作为「我作为新 owner 通知远端的次数」诊断用，可与 `evicted_by_remote` 跨实例对账验证 Pub/Sub 可达性。

### C. outcome 标签 7 值（含 cancelled / timeout）

| outcome | 触发场景 |
|---------|----------|
| `succeeded` | RESUME 完整完成 |
| `rejected_expired` | record 在 Redis 中已过期 |
| `rejected_mismatch` | `playerIdFromToken != record.playerId` |
| `rejected_owner_other` | 跨实例且本提案前的旧行为（B2 之后理论上很少触发） |
| `degraded_to_new` | 客户端带 flowId 但服务端走 NEW 路径 |
| `cancelled` | 客户端在 RESUME 进行中主动断（如玩家退出 app） |
| `timeout` | 服务端处理超时（待 future SLA：超过 `gate.flow.observability.resume-timeout-ms`） |

### D. replay source 加 `merged`

`source=merged` 表示一次跨实例 RESUME 在 buffer + offline 两阶段重放完成后的**总合计**事件，用于追问「这次跨实例 RESUME 一共补了多少帧」，与 `kind=cross_gw` 的 resume_total 1:1 对应。

### E. 立即下线 3 个旧指标

| 旧指标 | 被谁替代 |
|--------|----------|
| `gate_flow_total{event=cross_takeover, reason=*}` | `gate_flow_takeover_total{result=*}` |
| `gate_flow_buffer_replay_total` | `gate_flow_replay_total{source=buffer, outcome=replayed}` |
| `gate_flow_resume_latency_ms`（旧无 tag 版本） | `gate_flow_latency_resume_ms{kind=*}` |

> 其他 `gate_flow_total{event=new|resumed|destroyed|detached|buffer_*, reason=*}` 子集 **保留**（lifecycle 事件计数，与新指标是不同抽象层级），`gate_flow_buffer_ack_trimmed_total` / `gate_flow_buffer_overflow_total` / `gate_flow_buffer_dropped_total` / `gate_flow_active` / `gate_flow_attached` / `gate_flow_offline_*` 全部保留。

### F. 新增独立 logger `gate.cross.event`

与 `gate.flow.event` **同顶层并列**（不是 `gate.flow.cross` 子级），便于 ELK pipeline 用独立索引 / appender：

- `event=cross.takeover.publish flowId=... previousOwnerGateId=... newOwnerGateId=...`
- `event=cross.evict.received flowId=... newOwnerGateId=... localOwner=...`
- `event=cross.takeover.result flowId=... result=succeeded ageMs=... bufferReplayed=N offlineReplayed=M`

`gate.flow.event` 原 `event=flow.resumed crossTakeover=true ...` 这种**继续保留**（保持 lifecycle 日志一致），新 logger 是补充诊断渠道，不是替代。

### G. Histogram SLO bucket（custom-fine）

`10ms, 25ms, 50ms, 100ms, 250ms, 500ms, 1s, 2s, 5s` —— 适用于两个新 Timer。允许通过 `gate.flow.observability.histogram-slo` 覆盖。

### H. per-metric 开关粒度

```yaml
gate:
  flow:
    observability:
      enabled: true                       # master switch
      takeover-total-enabled: true        # gate_flow_takeover_total
      resume-total-enabled: true          # gate_flow_resume_total
      replay-total-enabled: true          # gate_flow_replay_total
      latency-enabled: true               # 两个 Timer
      cross-logger-enabled: true          # gate.cross.event logger
      histogram-slo: 10ms,25ms,50ms,100ms,250ms,500ms,1s,2s,5s
      resume-timeout-ms: 3000             # outcome=timeout 触发阈值
```

任一 sub-switch=false 时对应 Meter 不注册（零开销），master=false 时全部关闭（回滚 escape hatch）。

## 协议 / 兼容性

- **不影响**客户端协议、二进制帧格式、RESUME 业务逻辑、Redis Stream schema；
- **影响 Grafana / 告警查询**：使用旧 metric 名的 PromQL 必须迁移到新名（见 §8 文档）。本提案配套迁移清单（Migration 一节）。

## Capabilities 影响

- **新增 capability**：`gate-flow-observability`（含本提案 ADDED Requirements）。
- **影响既有 capability**（spec 文字描述需在各自 archive 时同步修订）：
  - `gate-resume-reconnect`：`### Requirement: 可观测性最低要求` 中 `gate_flow_resume_latency_ms` 文字应改为 `gate_flow_latency_resume_ms{kind=*}`；
  - `gate-flow-downstream-buffer`：`### Requirement: RESUME 成功后从 buffer 重放` 与 `### Requirement: buffer 可观测性` 中 `gate_flow_buffer_replay_total` 文字应改为 `gate_flow_replay_total{source=buffer, outcome=replayed}`；
  - `gate-flow-cross-instance`：`### Requirement: 跨实例迁移可观测性` 中 `gate_flow_cross_takeover_total{outcome}` 改为 `gate_flow_takeover_total{result}` + 6 值枚举；`### Requirement: 跨实例 RESUME 后 buffer 冷启动语义` 中 `gate_flow_buffer_replay_total` 文字同步替换。
  - **当前流程限制**：上述 4 个 capability 仍在 `changes/` 阶段未 archive，OpenSpec strict MODIFIED workflow 要求 source 已在 `openspec/specs/`。本提案采用 pragmatic 折中：spec 仅写 ADDED；上述文字迁移在 **archive 阶段** 由人工执行 cleanup（详见 tasks §9）。如团队希望严格 spec-level conformance，可在本提案合并后先 archive 4 个老 change，再走一次专门的 `refine-flow-observability-cleanup` change 做 MODIFIED。

# Impact

- **修改代码**：
  - `FlowSessionManager`（init 注册新 meter / `resume`/`crossTakeover`/`evictByCrossInstanceTakeover` 打新指标 + 写 `gate.cross.event` logger；**删除**旧 emit `cross_takeover` 路径与单 `resumeLatencyTimer` 旧字段；`replayPending` 改为 fine-grained outcome emit）；
  - `OfflineMessageService`（`replayAndMerge` / `flushBufferToOffline` 通过 `FlowSessionManager.recordReplay(source, outcome, count)` 共享 metric；新增 `source=merged` 汇总点）；
  - `FlowEvictListener`（emit `gate_flow_takeover_total{result=evicted_by_remote}` + `gate.cross.event` 日志）；
  - `FlowEvictPublisher`（写 `gate.cross.event publish` 日志 + emit `result=evicted_remote`）；
  - **删除旧指标 emit**：搜索并移除 `gate_flow_buffer_replay_total` 计数、单 Timer `gate_flow_resume_latency_ms` 注册、`emitCounter("cross_takeover", ...)` 调用。

- **新增代码**：
  - `com.clawai.gatedemo.gate.flow.metrics.FlowMetrics`（指标常量 + 标签字典 + 缓存 + mapping + noop）；
  - `GateConfig.FlowConfig.ObservabilityConfig`（6 个 sub-switch + bucket + timeout）。

- **不修改**：业务路径（RESUME / cross-takeover / offline merge 行为）、协议、Redis schema、`FlowSession` / `DownstreamBuffer` / `RedisFlowStore` 公开 API。

- **可观测性净增量**：+5 个 metric 系列、−3 个旧 metric 系列、+1 个 logger、+6 个配置开关。

- **测试**：新增 6+ 用例覆盖 mapping 正确性 + per-metric switch + cancelled/timeout outcome + merged source；扩展现有 `FlowSessionManagerTest` / `OfflineMessageServiceTest` / `FlowEvictListenerTest`。

- **文档**：
  - 新增 `docs/observability-flow-metrics.md`（label 字典 + Grafana PromQL 推荐表达式 + Migration 表）；
  - 更新 `docs/flow-session-resume.md` 末尾追加 Observability 小节，指向新文档；
  - tasks.md §9 列出 archive 阶段的 cleanup checklist。

- **Breaking change 警示**：使用 `gate_flow_total{event=cross_takeover}` / `gate_flow_buffer_replay_total` / `gate_flow_resume_latency_ms` 的 Grafana 面板 / Prometheus 告警规则**必须** 在本变更上线前迁移。

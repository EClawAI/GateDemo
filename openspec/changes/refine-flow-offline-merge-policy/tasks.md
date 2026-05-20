## 1. 配置

- [x] 1.1 `GateConfig.FlowConfig` 新增 `OfflineConfig`（enabled/retentionDays/reloginThreshold/replayBatchSize/flushOnDestroy/flushOnCrossEvict）
- [x] 1.2 `application.yml` 暴露 `gate.flow.offline.*`，含环境变量覆盖
- [x] 1.3 启动日志输出 offline 配置摘要（`OfflineMessageService.logConfigSummary @PostConstruct`）

## 2. Redis Stream 层（MessageQueueProducer）

- [x] 2.1 新增常量 `OFFLINE_FLOW_STREAM_KEY = "game:offline:flow"` + helper `offlineFlowKey(flowId)`
- [x] 2.2 新增 `saveOfflineFrame(flowId, playerId, gwSeq, msgId, encodedFrame)` + 设置 TTL
- [x] 2.3 新增 `readOfflineFrames(flowId, afterGwSeq, limit)` + 返回 `OfflineEntry` record
- [x] 2.4 新增 `deleteOfflineEntries(flowId, recordIds)`（XDEL batch）
- [x] 2.5 新增 `maxOfflineGwSeq(flowId)`（XRANGE 全扫；后续可优化为 sidekey）
- [x] 2.6 `saveOfflineMessage` 标记 `@Deprecated(forRemoval=false)`（保留为内部 `OfflineMessageService.storeForOffline` 的实现底座）

## 3. OfflineMessageService

- [x] 3.1 注入 `MessageQueueProducer`, `OfflineConfig`, `FlowSessionManager`（lazy）, `MeterRegistry`
- [x] 3.2 `storeForDetached(session, message)` —— stamp gwSeq + saveOfflineFrame + metric
- [x] 3.3 `storeForOffline(playerId, message)` —— 走 playerId stream + 无 gwSeq + metric
- [x] 3.4 `flushBufferToOffline(session, reason)` —— 遍历 buffer entries（gwSeq > lastSeqAnchor）写入 + 返回 flushed 计数
- [x] 3.5 `replayAndMerge(session, channel, fromSeq)` —— 读 + 过滤 + 升序写 + XDEL + 返回 `ReplayResult`
- [x] 3.6 `maxGwSeq(flowId)` —— 委托 producer
- [x] 3.7 `onPlayerOnline` 使用 `OfflineConfig.reloginThreshold` 替代硬编码 200
- [ ] 3.8 `handleNormalMode` —— drain playerId 兜底 stream + 投递 + XDEL（MVP 阶段保留 stub，避免与 game push 重复；待 game-service 增加确认机制后补全）

## 4. PlayerService.sendToPlayer 路由

- [x] 4.1 重写为 ATTACHED / DETACHED / no-session 三分支
- [x] 4.2 DETACHED 分支 → `offlineMessageService.storeForDetached`
- [x] 4.3 no-session 分支 → `offlineMessageService.storeForOffline`
- [x] 4.4 文档注释更新

## 5. FlowSessionManager 集成

- [x] 5.1 setter 注入 `OfflineMessageService`（`@Autowired(required=false)`，单测可不设）
- [x] 5.2 `destroy(session, reason)` 在 reason ∈ {detached_ttl, max_ttl} 时调用 `flushBufferToOffline`
- [x] 5.3 `evictByCrossInstanceTakeover` 同上（reason=cross_takeover）
- [x] 5.4 `resume` 在 `replayPending` 后调用 `replayAndMerge`
- [x] 5.5 跨实例 RESUME 新建 session 时，`seedNextGwSeq(max(record.lastSeqAnchor, maxGwSeq))`
- [x] 5.6 `ResumeResult` 扩展为含 offline replayed 计数（合并到 replayedCount，日志区分 bufferReplayed/offlineReplayed）

## 6. GateNettyWebSocketHandler

- [x] 6.1 NEW 路径完成后调用 `OfflineMessageService.onPlayerOnline(playerId, channel)`（drain 兜底 stream + 超阈值 RELOGIN）
- [x] 6.2 RESUMED 路径不调用 onPlayerOnline（merge 已在 resume 内部完成）

## 7. 可观测性

- [x] 7.1 metrics：`gate_flow_offline_stored_total{reason}`、`gate_flow_offline_replayed_total`、`gate_flow_offline_dropped_total{reason}`、`gate_flow_offline_flush_total{reason}`
- [x] 7.2 结构化日志：`flow.offline.stored` / `flow.offline.replayed` / `flow.offline.flushed`
- [x] 7.3 `/debug/flows` 增加 `offlineCount` 字段（注入 `OfflineMessageService.offlineFlowCount`，dev profile 可见）

## 8. 测试

- [x] 8.1 单元：`OfflineMessageServiceTest` —— storeForDetached / storeForOffline / flushBufferToOffline / replayAndMerge / maxGwSeq / Redis 不可用降级（13 用例）
- [x] 8.2 单元：新增 `PlayerServiceTest` —— ATTACHED / DETACHED / 无 session 三分支 + offline 缺失时回退（6 用例）
- [x] 8.3 单元：扩展 `FlowSessionManagerTest` —— destroy detached_ttl flush + cross-evict flush + resume 合流 + 跨实例 nextGwSeq 恢复 + new_takeover 不 flush（5 新增）
- [ ] 8.4 单元：`MessageQueueProducerTest`（依赖 Spring data redis；建议在 Testcontainers 中跑，与 8.5 合并）
- [ ] 8.5 集成（Testcontainers，可人工运行）—— 场景 T1-T10 端到端

## 9. 文档

- [ ] 9.1 `docs/flow-session-resume.md` 增「offline 合流策略」章节（统一 docs 整理时补）
- [ ] 9.2 `docs/redis-key-design.md` 标注 `game:offline:flow:*` key 与字段
- [ ] 9.3 `docs/优化.md` 中相关「离线消息空壳」项移到「已完成」
- [ ] 9.4 `openspec validate refine-flow-offline-merge-policy --strict` —— 已 PASS

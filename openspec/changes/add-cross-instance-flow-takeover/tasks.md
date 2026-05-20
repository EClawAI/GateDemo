## 1. Redis 层

- [x] 1.1 新建 `gate-service/src/main/resources/lua/flow_cross_takeover.lua`
- [x] 1.2 `RedisFlowStore` 装载并暴露 `crossTakeover` 方法 + `CrossTakeoverResult` record
- [x] 1.3 对 `RedisFlowStore` 测试 stub 增加 `crossTakeover` 内存实现

## 2. Pub/Sub

- [x] 2.1 新 `FlowEvictPublisher`（薄封装 `StringRedisTemplate.convertAndSend`）
- [x] 2.2 新 `FlowEvictListener` 实现 `MessageListener`，含 JSON 解析、self-skip 与 metric
- [x] 2.3 新 `FlowEvictPubSubConfig` 注册 `RedisMessageListenerContainer` + 订阅 channel
- [x] 2.4 配置：`GateConfig.FlowConfig.CrossConfig`（`enabled` / `evictChannel` / `publishSelfEvict`）

## 3. FlowSessionManager 改造

- [x] 3.1 移除 `record.ownerGateId() != self → REJECTED_OWNER_OTHER` 早退
- [x] 3.2 引入 `crossTakeover` 分支：`owner_same/owner_changed → 走 Phase A` / `expired → REJECTED_EXPIRED` / `redis_unavailable → REJECTED_OWNER_OTHER`
- [x] 3.3 新增 `evictByCrossInstanceTakeover(flowId, newOwnerGateId)` —— 仅清本地、不动 Redis
- [x] 3.4 owner_changed 后调用 `FlowEvictPublisher.publish`

## 4. 配置

- [x] 4.1 `application.yml` 暴露 `gate.flow.cross.*`
- [ ] 4.2 启动日志输出当前 channel 名 / 是否启用 cross（B2 收尾时人工补；属增强项）

## 5. 可观测性

- [x] 5.1 metrics：`gate_flow_cross_takeover_total{outcome}`、`gate_flow_cross_evict_published_total`、`gate_flow_cross_evict_received_total{ignored?}`、`gate_flow_cross_evict_invalid_total`
- [x] 5.2 日志：`flow.cross_takeover`、`flow.evict.published`、`flow.evict.received`
- [ ] 5.3 `/debug/flows` 端点不变；新增 `/debug/flows/cross-info` 返回当前订阅状态（dev profile）（B2 收尾时人工补）

## 6. 测试

- [x] 6.1 单元：`FlowSessionManagerTest` 新增 `resume_crossTakeover_ownerChanged_succeeds`
- [x] 6.2 单元：`FlowSessionManagerTest` 新增 `evictByCrossInstanceTakeover_*`（含 byplayer guard）
- [x] 6.3 单元：`FlowEvictListenerTest`（JSON 解析 + self skip + unknown flow）
- [ ] 6.4 集成（Testcontainers）：两个 manager 实例（gate-01/gate-02）共享 Redis，验证 RESUME 漂移后 byplayer 指向新 owner，且老 manager 关闭原 channel（依赖 Testcontainers/Redis；推荐人工/CI 运行）

## 7. 文档

- [ ] 7.1 `docs/flow-session-resume.md` 增「跨实例 RESUME」章节（待文档统一整理时补）
- [ ] 7.2 `docs/redis-key-design.md` 提及 `gate:flow:evict` 通道（待文档统一整理时补）
- [ ] 7.3 `openspec validate add-cross-instance-flow-takeover --strict`（统一在所有 change 完成后批量校验）

# Why

Phase A 引入了 `FlowSession`（NEW/RESUME/DETACHED），B1 给每帧打了 `gwSeq` 并在 RESUME 时从 in-memory `DownstreamBuffer` 重放未 ACK 帧；B2 让 owner 跨实例迁移。但**离线消息路径**仍是空壳：

1. `OfflineMessageService.handleNormalMode` 是空 stub，`onPlayerOnline` 永远不会被调用；
2. `PlayerService.sendToPlayer` 在 DETACHED / 无 FlowSession 时直接 **丢弃**（仅 warn 日志）；
3. B2 evict / TTL `destroy` 时，buffer 中的未 ACK 帧会随内存销毁而丢失；
4. 跨实例 RESUME 到新 gate 时，新 gate 的 buffer 是冷的，老 gate buffer 已被 evict —— 客户端会观察到「DETACH 期间消息凭空消失」。

需要把 Phase A/B1/B2 留下的「离线写入 + 跨实例补齐」缺口闭合，但**不破坏** B1 的 in-memory 路径性能：ATTACHED 状态仍然只走 buffer + 直写 channel，离线 stream 只在 DETACHED / 无 session / 跨实例补齐时触发。

# What Changes

## 行为变化

1. **统一下行入口路由（PlayerService）**：
   - `ATTACHED` + active Channel → 与 B1 相同，`FlowSessionManager.writeDownstream`（buffer + 直写）；
   - `DETACHED`（本地仍有 FlowSession）→ 走新 `OfflineMessageService.storeForDetached(session, message)`：仍由 `session.incrementAndGetGwSeq()` 分号 + stamp header，但**不**入 in-memory buffer，而是写入 Redis Stream；
   - 无 FlowSession（destroy 后 / 跨实例已 evict / 从未登录）→ 走 `storeForOffline(playerId, message)`：不 stamp gwSeq（无法确定下一次 RESUME 的 flowId），写入 Redis Stream 等下次首登 / RESUME 时补齐。

2. **RESUME 合流（FlowSessionManager.resume）**：
   - 现有 `replayPending`（buffer）先跑；
   - 之后调用新 `OfflineMessageService.replayAndMerge(session, channel, lastClientRecvSeq, alreadyReplayedToSeq)`：
     - 读 Redis Stream `game:offline:messages:{flowId}`（按 flowId 隔离，避免误投到顶号后的新 flow）；
     - 过滤 `gwSeq > max(lastClientRecvSeq, alreadyReplayedToSeq)`；
     - 按 gwSeq 升序写入新 channel；
     - 成功后 `XDEL` 已投递条目。

3. **destroy / cross-evict 时把 buffer flush 到 offline stream**：
   - `FlowSessionManager.destroy(session, reason)`：当 `reason` ∈ `{detached_ttl, cross_takeover}` 且 buffer 还有 `gwSeq > lastSeqAnchor` 的帧，先 flush 到 offline stream，再释放 buffer；
   - 顶号（`new_takeover`）= 玩家主动重登，老 buffer 直接丢（与现状一致）。

4. **跨实例 RESUME 的 nextGwSeq 恢复**：
   - 新 gate 接 RESUME 后，若本地 buffer 空但 offline stream 非空，则 `session.nextGwSeq` 初始化为 `max(offline.gwSeq)`，避免新 push 与老条目 seq 冲突。

5. **Relogin 阈值统一**：
   - `OfflineMessageConfig.threshold` 实际生效（替换 `OfflineMessageService` 中硬编码的 `THRESHOLD=200`）；
   - 超阈值时仍按现行 `handleReloginMode` 行为下发 RELOGIN_REQUIRED 并 `XDEL`。

## 协议 / 兼容性

- **协议不变**：B1 已经在 `MessageHeader` flag 位中预留 `FLAG_HAS_GW_SEQ`，所有 offline 流回放的帧仍按二进制协议透传；
- 老客户端（未协商 GW_SEQ）的 RESUME 行为不变，offline 路径不会启用 gwSeq stamp；
- offline stream 字段新增 `gw_seq`（long）与 `flow_id`（string），老条目（无 gw_seq）按 seq=0 处理，按时间顺序投递。

## Redis Key Schema 调整

- 新引入：`game:offline:flow:{flowId}` —— 按 flowId 隔离，对应单一 FlowSession 寿命；
- 兼容保留：`game:offline:messages:{playerId}` —— 退化路径（无 session 时使用），同 playerId 跨 flow 累积；
- 两类 key TTL 默认 7 天（`offline-message.retention-days`），过期由 Redis 自然回收。

## Capabilities 影响

新增 capability `gate-flow-offline-merge`（不影响 `gate-resume-reconnect` / `gate-flow-downstream-buffer` / `gate-flow-cross-instance`）。

# Impact

- **修改**：`PlayerService`、`FlowSessionManager`（destroy / resume / cross-evict）、`OfflineMessageService`、`MessageQueueProducer`（新增 flow-scoped 离线读写）、`OfflineMessageConfig`（接入 Spring 配置）、`GateConfig.FlowConfig`（新增 `OfflineConfig`）、`application.yml`；
- **新增 capability**：`gate-flow-offline-merge`；
- **不修改**：协议二进制格式、`MessageHeader`、`FlowSession.attach/detachIfMatches`、B1 in-memory buffer 主路径；
- **可观测性**：新增 metric `gate_flow_offline_stored_total{reason}`、`gate_flow_offline_replayed_total`、`gate_flow_offline_dropped_total{reason}`、`gate_flow_offline_flush_total{reason}`；
- **测试**：新增 `OfflineMessageServiceTest` + 扩展 `FlowSessionManagerTest`/`PlayerServiceTest` 覆盖 4 个场景（A/B/C/D）；
- **集成测试**（推荐人工）：DETACH → 多次 push → RESUME，验证收到所有帧且无重复。

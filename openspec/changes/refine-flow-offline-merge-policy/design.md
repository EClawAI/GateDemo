# Design: B3 — FlowSession 与 Offline 消息合流

## 1. 背景与目标

### 1.1 当前缺口

- `PlayerService.sendToPlayer`：未 ATTACHED 即丢；
- `OfflineMessageService.handleNormalMode`：空 stub；
- `MessageQueueProducer.saveOfflineMessage`：零调用方；
- B1 buffer 在 `destroy` / B2 evict 时随内存释放，未 ACK 帧消失；
- 跨实例 RESUME 到新 gate 时，老 gate 的 buffer 已被 evict，客户端会观察到 DETACH 期间消息丢失；
- 无法处理「玩家长时间离线 → 服务端期间产生大量 push」的常规离线消息场景。

### 1.2 设计目标

| 目标 | 衡量指标 |
|------|----------|
| 闭合 DETACHED → RESUME 路径，丢失率 0 | 集成场景 D：DETACH 期间 N 条 push，RESUME 后客户端收到 N 条 |
| 闭合「跨实例 RESUME」漂移路径 | gate-01 push → gate-02 RESUME 收到，gwSeq 不冲突 |
| 不破坏 B1 主路径性能 | ATTACHED writeDownstream 仍 O(1)，不引入 Redis 调用 |
| 兼容老客户端（无 GW_SEQ）| 老客户端 RESUME 行为不变；offline 路径不 stamp gwSeq |
| 不重复投递 | 同一 gwSeq 在 buffer ∪ offline 中最多出现一次（写时互斥） |
| 不引外部依赖 | 复用 `StringRedisTemplate` + `RedisTemplate<String,Object>`，沿用 Redis Stream |

## 2. 整体架构

### 2.1 下行路径决策树

```
sendToPlayer(playerId, msg)
  │
  ├─ session == null
  │    └─ storeForOffline(playerId, msg)      // 无 flowId，无 gwSeq，等下次首登
  │
  ├─ session.state == ATTACHED && channel active
  │    └─ FlowSessionManager.writeDownstream  // B1：buffer + 直写
  │
  └─ session.state == DETACHED (or channel dead)
       └─ storeForDetached(session, msg)      // stamp gwSeq，写 Redis Stream（按 flowId）
```

**核心不变量**：每条 downstream message 在生命周期内至多写入两处之一（buffer 或 offline stream），且至多一次；gwSeq 单调（由 `FlowSession.nextGwSeq` AtomicLong 派号）。

### 2.2 RESUME 合流

```
resume(flowId, playerId, lastClientRecvSeq, channel, features)
  │
  ├─ 校验 + attach (Phase A/B2 流程)
  ├─ buf.ackUpTo(lastClientRecvSeq)
  ├─ ReplayResult bufR = replayPending(session, channel, lastClientRecvSeq)         // B1
  ├─ long fromSeq = max(lastClientRecvSeq, bufR.toSeq)
  └─ ReplayResult offR = offlineMessageService.replayAndMerge(session, channel, fromSeq)  // B3 NEW
```

**合流策略**：先放 in-memory buffer（最新、最快），再补 offline stream（旧、慢）；gwSeq 升序。两者按 flowId 隔离，理论上不会重叠（write path 互斥）；防御性地按 `gwSeq > fromSeq` 过滤。

### 2.3 destroy / cross-evict flush

```
destroy(session, reason):
  if reason in {detached_ttl, cross_takeover} and buffer not empty:
      offlineMessageService.flushBufferToOffline(session)
  release buffer; remove from maps; redisStore.destroy
```

为什么不在 NEW takeover 时 flush？—— `new_takeover` 由同一 playerId 主动重登触发，新登录的 flowId 是新的，老 buffer 中的帧对新 flow 无意义；继续保留只会让客户端收到「上次会话」的旧帧，破坏语义。

### 2.4 跨实例 nextGwSeq 恢复

```
resume on new gate, local == null, record != null, crossTookOver == true:
    create new FlowSession with ownerForLocal = self gateId
    long offlineMaxSeq = offlineMessageService.maxGwSeq(flowId)   // 0 if absent
    session.nextGwSeq.set(max(record.lastSeqAnchor, offlineMaxSeq))
```

避免：老 gate 写到 gwSeq=100，evict 后新 gate 从 0 重新派号，与 offline 中既有 gwSeq=50 冲突，merge 时出现「同 gwSeq 不同 payload」的歧义。

## 3. Redis Key 与字段设计

### 3.1 新增：flowId 隔离的 offline stream

| Key | 类型 | 字段 | 说明 |
|-----|------|------|------|
| `game:offline:flow:{flowId}` | Stream | `flow_id`, `player_id`, `gw_seq` (long), `msg_id` (int), `frame` (bytes, 二进制 encoded) | 按 flowId 隔离；TTL = `offline-message.retention-days`（默认 7 天） |

**为什么用 flowId 而不是 playerId？**
- 顶号（NEW takeover）后老 flowId 的 offline 不该投递给新 flow，自然隔离；
- 跨实例迁移时 flowId 不变，新 gate 直接读同 key；
- `flowId` UUIDv4 已足以全局唯一。

### 3.2 保留：playerId 兜底 stream

| Key | 类型 | 字段 | 说明 |
|-----|------|------|------|
| `game:offline:messages:{playerId}` | Stream | `player_id`, `msg_id`, `data`, `timestamp` | 退化路径：完全无 session 时使用；NEW 登录时合并到当前 flow 的 offline 视图 |

**何时写入？** 只在 `storeForOffline(playerId, msg)` 路径（无 FlowSession）。常规 push 永远走 flowId 隔离的 stream。

**何时清理？** `OfflineMessageService.onPlayerOnline(playerId, channel)` 在 NEW 首登时 drain；超阈值（`offline-message.threshold`）触发 relogin。

### 3.3 字段顺序

Redis Stream 不要求字段顺序，但 consumer 解析时按 `gw_seq` 排序优先于 stream entry id 时间顺序（兼容老 entries 无 gw_seq 字段 → 视作 0）。

## 4. API 设计

### 4.1 OfflineMessageService 新接口

```java
public class OfflineMessageService {

    /** B3：DETACHED 状态下写入 offline stream，stamp gwSeq + 入 flowId 隔离 stream。 */
    public boolean storeForDetached(FlowSession session, WrappedMessage message);

    /** B3：无 FlowSession，写入 playerId 兜底 stream（不 stamp gwSeq）。 */
    public boolean storeForOffline(long playerId, WrappedMessage message);

    /** B3：destroy/evict 前 flush buffer 中未 ACK 帧到 offline stream（保留 gwSeq）。 */
    public int flushBufferToOffline(FlowSession session);

    /** B3：RESUME 合流读 flowId 隔离 stream，按 gwSeq > fromSeq 过滤、升序写 channel。 */
    public ReplayResult replayAndMerge(FlowSession session, Channel channel, long fromSeq);

    /** B3：跨实例 nextGwSeq 恢复时查 offline stream 中最大 gwSeq；不存在返回 0。 */
    public long maxGwSeq(String flowId);

    // 保留：现有 onPlayerOffline / onPlayerOnline / handleReloginMode 的 relogin 阈值逻辑
}
```

### 4.2 MessageQueueProducer 新接口

```java
public class MessageQueueProducer {

    /** B3：flowId 隔离 stream 写入（字段含 gw_seq）。 */
    public String saveOfflineFrame(String flowId, long playerId, long gwSeq,
                                   int messageId, byte[] encodedFrame);

    /** B3：flowId 隔离 stream 读取（按 stream 顺序）。 */
    public List<OfflineEntry> readOfflineFrames(String flowId, long afterGwSeq, int limit);

    /** B3：删除指定 RecordId 的 offline 条目（XDEL）。 */
    public int deleteOfflineEntries(String flowId, List<String> recordIds);

    /** B3：查询某 flow stream 的最大 gw_seq；O(stream.size)。 */
    public long maxOfflineGwSeq(String flowId);

    public record OfflineEntry(String recordId, long gwSeq,
                               int messageId, byte[] frame) {}
}
```

### 4.3 PlayerService 路由

```java
public boolean sendToPlayer(Long playerId, WrappedMessage message) {
    if (playerId == null) return false;
    FlowSession session = flowSessionManager.getByPlayerId(playerId);
    if (session == null) {
        return offlineMessageService.storeForOffline(playerId, message);
    }
    if (session.getState() == FlowSession.State.ATTACHED
            && session.getCurrentChannel() != null
            && session.getCurrentChannel().isActive()) {
        return flowSessionManager.writeDownstream(session, message);
    }
    return offlineMessageService.storeForDetached(session, message);
}
```

### 4.4 FlowSessionManager 改造点

- `destroy(session, reason)`：在 `redisStore.destroy` 前调用 `offlineMessageService.flushBufferToOffline(session)`（仅 `detached_ttl` / `cross_takeover` 两个 reason）；
- `evictByCrossInstanceTakeover`：同上，flush 后再清本地；
- `resume`：在 `replayPending` 之后调用 `offlineMessageService.replayAndMerge`；
- `resume` 跨实例新建 session 时，调用 `offlineMessageService.maxGwSeq(flowId)` 初始化 `nextGwSeq`。

### 4.5 配置

```java
public static class OfflineConfig {
    private boolean enabled = true;
    private int retentionDays = 7;
    private int reloginThreshold = 200;
    private int replayBatchSize = 100;     // 单次 XRANGE batch
    private boolean flushOnDestroy = true;
    private boolean flushOnCrossEvict = true;
}
```

```yaml
gate:
  flow:
    offline:
      enabled: true
      retention-days: 7
      relogin-threshold: 200
      replay-batch-size: 100
      flush-on-destroy: true
      flush-on-cross-evict: true
```

## 5. 关键决策与权衡

### 5.1 为什么按 flowId 隔离 offline stream，而不是按 playerId？

| 维度 | flowId 隔离 | playerId 隔离 |
|------|-------------|---------------|
| 顶号语义 | ✅ 老 flow 的 offline 自然废弃（不会被新 flow 拉到） | ❌ 顶号后需手动判断哪些条目属于老 flow |
| 跨实例 | ✅ flowId 全局唯一，新 gate 直接读 | ✅ 同 playerId 即可读，但需 owner 校验 |
| 长期离线 | ❌ 玩家长期不登的话，老 flow 的 offline 不会被清理（依赖 TTL） | ✅ 同 playerId 直接累积 |
| 实现复杂度 | 简单 | 需 dedup |

**结论**：常规 push 走 flowId 隔离；只有「玩家彻底无 session」的极端边角（如 destroy 后才 push）走 playerId 兜底。两套 key + 两个 API，逻辑清晰。

### 5.2 RESUME 时合流次序

**先 buffer 后 offline**（buffer 优先）：
- buffer 是 ATTACHED 期间已经 stamped 的帧，时间最新；
- offline 是 DETACHED 期间 stamped 的帧；
- 按 gwSeq 升序排列时，buffer 的 gwSeq 区间一定小于 offline 的 gwSeq 区间（写时互斥），所以合流就是「append offline after buffer」。

防御性过滤 `gwSeq > fromSeq` 仍然保留，应对：
- 极端边角：destroy flush 把老 buffer 也写进 offline，与新 buffer 在 RESUME 时有重叠的极小窗口；
- 老 entries 没有 gwSeq 字段（视作 0）。

### 5.3 destroy / cross-evict 是否 flush

**默认 flush**（`flush-on-destroy=true`）：
- 优点：跨实例迁移 / TTL 边界不丢消息；
- 缺点：destroy 多一次 Redis 调用，可能拉长 graceful shutdown；

**MVP 实现按 sync 调用，配置可关**。后续若性能压力大可改异步队列。

### 5.4 nextGwSeq 跨实例恢复来源

| 来源 | 准确性 | 成本 |
|------|--------|------|
| `record.lastSeqAnchor`（客户端 ACK 锚点） | 偏小（客户端可能没 ACK 到最大已发） | O(1)，跟 record 已 HGET 出来 |
| `maxOfflineGwSeq(flowId)` | 准 | O(stream.size)，需要 XRANGE 扫一遍 |

**最终用两者最大值**：`session.nextGwSeq.set(max(record.lastSeqAnchor, offlineMax) + 1)`。

如果 offline stream 巨大（>10k 条目），扫一遍开销不可忽视——但 cross-instance 迁移本身是低频事件，可接受。后续可优化为「stream 元数据写入一个 sidekey 维护当前 max」。

## 6. 兼容性 / 迁移

| 场景 | B3 前 | B3 后 |
|------|--------|--------|
| 客户端无 GW_SEQ | RESUME 仅换绑，无 buffer 重放 | 同：offline 路径也不 stamp gwSeq，但仍按时间顺序投递（不阻塞老客户端） |
| Redis 不可用 | DETACHED push 直接丢；relogin 走老路径 | DETACHED push 仍丢但有 warn metric；replayAndMerge 直接返回 0，不影响 buffer 重放 |
| 升级灰度 | — | `gate.flow.offline.enabled=false` 时退化为 Phase A/B1 行为（write 仍调用，但所有 API 返回 false / 0） |
| 老 offline 条目无 `gw_seq` 字段 | — | 按 0 视为旧条目，全部 RESUME 时投递，投递后 XDEL |

## 7. 边界 / 不做

- **不**做 offline → game-service 反向通知（B3 范畴仅限 game→client 方向）；
- **不**实现 offline stream 的消费者组（单 gate 单 player，无需 group）；
- **不**实现 offline 压缩 / 合并；按帧投递；
- **不**做端到端去重（gwSeq 已足够）；
- **不**改动协议二进制格式；
- **不**直接 hook `evictByCrossInstanceTakeover` 触发 flush —— 由 `FlowSessionManager` 调度（保持 evict listener 极简）。

## 8. 测试矩阵

| ID | 场景 | 验证点 |
|----|------|--------|
| T1 | ATTACHED push | 不写 offline stream，与 B1 一致 |
| T2 | DETACHED push N 条 | offline stream 长度 == N，每条都有 gw_seq |
| T3 | DETACH → push → RESUME | replay buffer，再 drain offline，channel 收到所有 N 条，XDEL 清空 |
| T4 | 无 session push | 走 playerId stream；下次 NEW 登录 → handleNormalMode 投递 |
| T5 | destroy `detached_ttl` 触发 flush | buffer 中未 ACK 帧出现在 offline stream |
| T6 | cross-evict 触发 flush | 同 T5，但 reason 不同；并广播 evict |
| T7 | 跨实例 RESUME 恢复 nextGwSeq | 新 gate 派的 nextGwSeq > offline 最大值 |
| T8 | offline 超阈值 | 触发 relogin，XDEL 整 stream |
| T9 | Redis 不可用 | offline API 全返回 false / 0；buffer 路径继续可用 |
| T10 | 老条目无 gw_seq | RESUME 时按 0 视作旧条目，按时间顺序投递 |

## 9. 与之前 changes 的关系

| Change | 关系 |
|--------|------|
| `add-netun-resume-flow-session` (Phase A) | 依赖：FlowSession 状态机、Redis 元数据 |
| `add-flow-downstream-buffer` (B1) | 依赖：DownstreamBuffer / gwSeq 派号 / replayPending |
| `add-cross-instance-flow-takeover` (B2) | 互补：B2 解决了 owner 漂移，本 change 解决「漂移期间消息不丢」 |
| 后续 B4 multi-transport | 不冲突；transport 抽象后 offline 路径仍按 flowId 隔离 |

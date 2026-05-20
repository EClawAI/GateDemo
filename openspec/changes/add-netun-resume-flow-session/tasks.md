## 1. 设计冻结与协议扩展

- [x] 1.1 评审 `proposal.md` / `design.md`，确认 **flow.detached-ttl-seconds = 60s**、**flow.max-ttl-seconds = 24h** 数值，必要时改后更新文档
- [x] 1.2 评审 `AuthRequest` / `AuthResponse` 字段命名（`flow_id` / `last_client_recv_seq` / `resume_status`）与枚举取值（`NEW` / `RESUMED` / `REJECTED_EXPIRED` / `REJECTED_MISMATCH` / `REJECTED_OWNER_OTHER`）
- [x] 1.3 在 `GateDemo/proto/gate_protocol.proto` 中以 **可选字段** 形式扩展 `AuthRequest` 与 `AuthResponse`；重新生成 Java/C# stub
- [x] 1.4 执行 `openspec validate add-netun-resume-flow-session --strict`

## 2. M1 — Redis Flow 存储与索引

- [x] 2.1 新增 `RedisFlowStore`：封装 `gate:flow:<flowId>` Hash 读写、`gate:flow:byplayer:<playerId>` 索引、`expiresAt` 续期；接口围绕 `create / loadByFlowId / loadByPlayerId / markDetached / destroy`
- [x] 2.2 编写 Lua 脚本 `flow_takeover.lua`：原子完成「关旧 byplayer + DEL 旧 flow hash + SET 新 byplayer + HMSET 新 flow hash」，单元/集成测试覆盖
- [x] 2.3 Redis 不可用降级路径（直通 NEW 不阻断 AUTH）+ 告警日志 + metrics 计数
- [x] 2.4 配置项落地：`gate.flow.detached-ttl-seconds`、`gate.flow.max-ttl-seconds`、`gate.flow.renewal-interval-seconds`（默认 15s）

## 3. M2 — FlowSession 内存对象与 Manager

- [x] 3.1 新增 `FlowSession`（POJO）：字段对齐 design §6；`currentChannelRef` 用 `AttributeKey` 与 Channel 反向绑定
- [x] 3.2 新增 `FlowSessionManager`（@Service）：`newFlow(playerId, gameId, channel)`、`resume(flowId, lastSeq, channel)`、`markDetached(channel)`、`destroyByPlayer(playerId, reason)`
- [x] 3.3 `FlowSessionManager` 启动一个 `ScheduledExecutor`：DETACHED 超时扫描、Redis renewal
- [x] 3.4 `PlayerService` 重构：保留对外 API（`sendToPlayer`、`hasPlayer`、`renewHeartbeat`），内部委托 `FlowSessionManager`；移除直接的 `ConcurrentMap<Long, Channel>` 暴露

## 4. M3 — AUTH 路径分叉（gate handler）

- [x] 4.1 `GateNettyWebSocketHandler#handleAuth` 解析新字段；按 `flow_id` 缺省/存在分叉到 NEW / RESUME
- [x] 4.2 NEW 路径：`FlowSessionManager.newFlow(...)` → 写 `AuthResponse{flow_id, resume_status=NEW}`
- [x] 4.3 RESUME 路径：`FlowSessionManager.resume(...)`；命中则 ATTACH 新 Channel 返回 `RESUMED`；未命中按对应 `REJECTED_*` 走 NEW 降级
- [x] 4.4 `channelInactive` 钩子：调用 `FlowSessionManager.markDetached(channel)`，不立即销毁 flow
- [x] 4.5 同实例顶号：NEW 路径中通过 Lua takeover 顺带关闭旧 Channel（若仍在本实例 ATTACHED）

## 5. M4 — 协议与客户端 demo

- [x] 5.1 `player-client` demo 持久化 `flow_id`（本地文件或内存均可，文档化策略）
- [x] 5.2 demo 自动重连脚本：断网后重连时 `AuthRequest` 携带保存的 `flow_id` 与最近一次 `last_client_recv_seq`
- [x] 5.3 README / docs 增补：弱网重连演示步骤、`resume_status` 含义

## 6. M5 — 可观测性与运维

- [x] 6.1 结构化日志：`event=flow.new|flow.resumed|flow.detached|flow.destroyed`，字段对齐 design §9
- [x] 6.2 metrics 暴露：`gate_flow_total{event,reason}`、`gate_flow_active`、`gate_flow_attached`、`gate_flow_resume_latency_ms`（接入现有 Micrometer / Prometheus 端点）
- [x] 6.3 `/health` / `/ready` 端点不变；新增 `/debug/flows`（仅在 dev profile 下开启）便于本地诊断
- [x] 6.4 docker-compose / k8s 资源说明：Redis key 前缀登记，容量评估补一节文档

## 7. 测试

- [x] 7.1 单元：`RedisFlowStore` Lua 脚本（用 embedded redis 或 mock）、`FlowSessionManager` 状态机
- [x] 7.2 集成：单 gate 实例下 NEW → 断 → 60s 内 RESUME → ATTACHED；超 TTL → REJECTED_EXPIRED 降级 NEW
- [x] 7.3 集成：顶号场景（两端先后 NEW）— 旧 Channel 被关、Redis 原子替换
- [x] 7.4 集成：跨实例 RESUME 被拒（`REJECTED_OWNER_OTHER`）并降级 NEW
- [x] 7.5 集成：Redis 不可用降级（启动一个不通的 Redis 端口或停 Redis 容器）

## 8. 文档与归档

- [x] 8.1 GateDemo `docs/` 下新增 `flow-session-resume.md`：协议字段、Redis schema、TTL 默认值、客户端集成指南
- [x] 8.2 评审 + 合并；执行 `openspec validate add-netun-resume-flow-session --strict`
- [ ] 8.3 `openspec archive add-netun-resume-flow-session`：将 delta 合并入 `openspec/specs/gate-resume-reconnect/spec.md`
- [ ] 8.4 （归档后另立小 change）建议在工作区 `openspec/specs/gatedemo/spec.md` 把「无状态网关」表述精确化为「集群级状态在 Redis；gate 实例可持 TTL 内 flow 缓存」；本 change 不直接修改跨工作区文件

# 集成限流与熔断 (integrate-ratelimit-circuitbreaker)

## Context

- **当前状态**：gate-service 中 RateLimiter（滑动窗口算法）和 CircuitBreaker（CLOSED/OPEN/HALF_OPEN 状态机）已实现，但未被任何 Handler 或调用路径接入。WebSocket 消息入口无限流保护，易遭受洪水攻击；gRPC 调用 Game 时无熔断，Game 宕机时 Gate 持续重试，易造成级联故障与资源耗尽。
- **问题**：限流与熔断能力形同虚设，无法在真实流量和故障场景下发挥作用。
- **约束**：保持现有 WebSocket/gRPC 协议与消息格式；不改变 player-client 与 Game 的行为；需与现有 Netty Pipeline、gRPC Client 集成。

## Goals / Non-Goals

**Goals:**

- 将 RateLimiter 集成到 WebSocket Handler 消息处理入口，支持 per-player（按玩家 ID）和 global（全局限流）两级限流。
- 将 CircuitBreaker 包裹 gate-service 到 Game 的 gRPC 调用（如 sendGameMessage），失败或超时时自动熔断。
- 超限或被熔断时返回约定错误码并记录日志；熔断时返回降级响应，避免调用方长时间阻塞。

**Non-Goals:**

- 不在此 change 中实现 TCP 通道的限流（仅 WebSocket）。
- 不改变 RateLimiter、CircuitBreaker 的内部算法或 API。
- 不实现跨 Gate 实例的分布式限流（仅进程内）。

## Decisions

1. **RateLimiter 集成点选在 GateNettyWebSocketHandler.channelRead0 入口**
   - 在解析 JSON 后、分发消息前，先执行 per-player 限流（若已认证则按 playerId，否则按 channelId 或 IP）；再执行 global 限流。
   - **理由**：统一入口，所有上行消息（auth/heartbeat/game_msg）均受控；心跳可豁免或使用更宽松策略，在实现中约定。放在编解码之后可避免对畸形数据浪费限流配额。

2. **CircuitBreaker 包裹 gRPC 发往 Game 的调用**
   - 在 GameGrpcClientPool 或 PlayerService 中，凡调用 Game gRPC 发送消息的地方，外包一层 CircuitBreaker：先 `allowRequest()`，失败则直接返回熔断错误；成功则执行 gRPC，根据结果 `recordSuccess()` 或 `recordFailure()`。
   - **理由**：熔断应保护下游 Game 及 Gate 自身，gRPC 是唯一下游依赖，在此包裹可统一生效。

3. **降级策略：返回约定错误码 + 结构化响应**
   - 限流超限：返回 `type: "error"` 且 `code: "RATE_LIMITED"`，可带 `message` 提示。
   - 熔断：返回 `type: "error"` 且 `code: "SERVICE_UNAVAILABLE"` 或 `GAME_UNAVAILABLE`，避免无限等待。
   - **理由**：客户端可据此做重试或提示；保持 JSON 格式与现有错误结构一致。

4. **配置化限流与熔断参数**
   - 通过 gate 配置（如 gate.ratelimit.*、gate.circuitbreaker.*）暴露 maxRequests、windowMs、failureThreshold、resetTimeoutMs 等；可由 DI 注入或配置文件加载。
   - **理由**：便于按环境调优，无需改代码。

## Risks / Trade-offs

- **[风险]** per-player 限流 key 使用未认证 channelId 时，恶意连接可消耗大量 key 导致内存膨胀 → mitigation：对未认证连接使用更严格的 global 限流，并设置 key 数量上限或 TTL 清理。
- **[权衡]**  heartbeat 参与限流可能影响正常心跳 → 将 heartbeat 豁免或单独配置更宽松的窗口，在实现中明确。
- **[风险]** 熔断后短时间内大量请求被拒绝，客户端频繁重试 → mitigation：降级响应中可附带 `retry_after`  hint，并在文档中建议客户端退避。

## Migration Plan

- **实现顺序**：1) 在 GateNettyWebSocketHandler 注入 RateLimiter，在 channelRead0 中添加 per-player 与 global 限流逻辑；2) 在 gRPC 调用处注入 CircuitBreaker 并包裹调用；3) 统一错误码与降级响应格式；4) 添加配置与日志。
- **部署**：无数据迁移；先部署 Gate 新版本，观察限流与熔断日志；建议在测试环境先压测验证阈值。
- **回滚**：通过配置将限流阈值调高或熔断关闭（若实现支持），或直接回退 Gate 部署。

## Open Questions

- heartbeat 是否参与 per-player 限流？建议 exempt，以保持连接不断开。
- 每个 Game 实例是否使用独立的 CircuitBreaker？若多 Game，按 gameId 维度的熔断更细粒度，但实现复杂度增加；可先全局限流一个熔断器。

## 1. RateLimiter 集成到 WebSocket Handler

- [ ] 1.1 在 GateNettyWebSocketHandler 中注入 RateLimiter（per-player）与全局 RateLimiter，通过 DI 或构造函数传入
- [ ] 1.2 在 channelRead0 中，解析 JSON 后、业务处理前，按 playerId（已认证）或 channelId（未认证）执行 per-player tryAcquire，失败则返回 RATE_LIMITED 错误并 return
- [ ] 1.3 对 global RateLimiter 执行 tryAcquire，失败则返回 RATE_LIMITED 错误并 return
- [ ] 1.4 对 type 为 heartbeat 的消息按配置决定是否豁免 per-player 限流
- [ ] 1.5 超限时构造并写入 `{"type":"error","code":"RATE_LIMITED","message":"..."}` 格式的 WebSocket 帧，并记录日志

## 2. CircuitBreaker 集成到 gRPC 调用

- [ ] 2.1 在 PlayerService 或 GameGrpcClientPool 的 gRPC 发送路径注入 CircuitBreaker
- [ ] 2.2 在发起 gRPC 调用前调用 circuitBreaker.allowRequest()，若为 false 则直接返回降级错误（不发起 gRPC）
- [ ] 2.3 调用成功后 recordSuccess()，失败或超时后 recordFailure()
- [ ] 2.4 熔断时向客户端返回 `{"type":"error","code":"SERVICE_UNAVAILABLE"}` 或 `GAME_UNAVAILABLE`，并记录日志

## 3. 配置与初始化

- [ ] 3.1 在 gate 配置中增加 gate.ratelimit.perPlayer.maxRequests、windowMs 与 gate.ratelimit.global.* 配置项
- [ ] 3.2 增加 gate.circuitbreaker.failureThreshold、resetTimeoutMs 配置项
- [ ] 3.3 在应用启动时（或 DI 模块）根据配置实例化 RateLimiter 与 CircuitBreaker，并注入到 Handler 与 gRPC 调用处
- [ ] 3.4 在应用关闭时调用 RateLimiter.shutdown() 清理资源

## 4. 文档与测试

- [ ] 4.1 在配置文档中说明限流与熔断参数含义及建议取值
- [ ] 4.2 补充 RateLimiter 在 Handler 内的集成测试（超限返回错误、未超限通过）
- [ ] 4.3 补充 CircuitBreaker 包裹 gRPC 的单元测试（OPEN 时返回降级、CLOSED 时正常调用）

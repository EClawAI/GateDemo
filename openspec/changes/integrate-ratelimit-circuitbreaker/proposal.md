## Why

RateLimiter 和 CircuitBreaker 已实现但未被调用：
- RateLimiter（滑动窗口）无 Handler 接入，无法防御洪水攻击
- CircuitBreaker（状态机）未包裹 gRPC 调用，Game 宕机时 Gate 持续发送请求，易造成级联故障

## What Changes

将 RateLimiter 与 CircuitBreaker 接入实际调用路径：
- RateLimiter 集成到 WebSocket handler，支持 per-player 与 global 限流
- CircuitBreaker 包裹 gRPC 调用，失败时自动熔断
- 熔断时返回降级响应，避免请求堆积

## 核心功能

1. **消息入口限流**
   - per-player 限流：单玩家消息频率控制
   - global 限流：全局限流保护
   - 超限时返回友好错误或丢弃

2. **gRPC 熔断**
   - 在 GameGrpcClient 或调用处包裹 CircuitBreaker
   - 根据失败率/超时自动打开熔断
   - 半开状态探测恢复

3. **降级响应**
   - 熔断时返回约定错误码或默认响应
   - 避免调用方长时间阻塞

## Impact

- 影响 `gate-service` WebSocket handler 接入 RateLimiter
- 影响 `gate-service` gRPC 调用处接入 CircuitBreaker

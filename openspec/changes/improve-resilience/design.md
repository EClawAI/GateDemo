# 韧性提升 (design.md)

## Context

- **当前状态**：各组件（Netty Server、gRPC Client、Redis 等）有各自的销毁回调，但无统一编排和 connection draining；gRPC 重连采用 `new Thread` + 固定延迟，无退避策略；PlayerService 对在线 WebSocket 连接数无上限控制。
- **问题**：关闭时可能强行中断正在处理的请求；gRPC 重连风暴在 Game 不可用时持续冲击；连接数 uncontrolled 可能导致 OOM 或资源耗尽。
- **约束**：不使用 Spring Boot 概念；需保持与现有 Netty、gRPC、Redis 实现的兼容。

## Goals / Non-Goals

**Goals:**
- 通过 JVM shutdown hook 统一编排关闭顺序（stop accept → drain connections → close EventLoopGroup），设置 30s 超时
- gRPC 重连：替换 `new Thread` + 固定延迟为 `ScheduledExecutorService` + 指数退避（初始 delay → 2x → 4x → maxDelay），设最大重试次数
- 在 WebSocket handler 的 `channelActive` 中检查当前连接数，超配置上限时拒绝新连接

**Non-Goals:**
- 不实现熔断、限流等更高级弹性能力
- 不改变 gRPC 协议或 WebSocket 协议
- 不引入 Spring 或第三方弹性库

## Decisions

1. **优雅关闭通过 JVM shutdown hook  orchestration**
   - 注册 JVM shutdown hook，在收到 SIGTERM 时依次执行：停止接受新连接 → 等待已有连接 drain（请求处理完或超时）→ 关闭 EventLoopGroup、gRPC Channel、Redis 客户端等。
   - **理由**：与 Spring 解耦，适用纯 Java/Netty 栈；30s 超时避免长时间阻塞进程退出。

2. **gRPC 重连使用 ScheduledExecutorService + 指数退避**
   - 替换原有 `new Thread` + `Thread.sleep` 的固定延迟；使用 `ScheduledExecutorService.scheduleAtFixedRate` 或 `schedule` 实现重试；退避策略：初始 delay、2x、4x、…、上限 maxDelay；设置最大重试次数，超限后停止。
   - **理由**：避免重连风暴；集中调度，便于取消与资源释放。

3. **WebSocket 连接数上限检查**
   - 在 WebSocket handler 的 `channelActive` 中获取当前连接计数（原子变量或共享计数器）；若超过 `gate.ws.max-connections` 配置，则拒绝连接（关闭 channel 并返回错误响应）。
   - **理由**：防止单实例过载；在最早入口拦截，简单有效。

4. **关闭超时 30s**
   - 从开始关闭到强制结束，总超时 30 秒；drain 阶段若超时未完成，则强制关闭剩余连接。
   - **理由**：与容器编排（如 K8s terminationGracePeriodSeconds）常见值一致，避免被 SIGKILL 强杀。

## Risks / Trade-offs

- **[风险]** shutdown hook 与某些库的 hook 执行顺序冲突 → 将自定义 hook 的优先级和顺序明确，优先执行 stop accept，最后释放资源；必要时使用 `Runtime.getRuntime().addShutdownHook` 并确保单一 hook 协调所有组件。
- **[风险]** 指数退避可能导致恢复过慢 → 设 maxDelay 上限（如 60s），并允许配置初始 delay 与 maxRetries，便于调优。
- **[权衡]** 连接数上限拒绝可能影响用户体验 → 在错误响应中明确返回「服务繁忙」类信息，并建议客户端重试；可配合负载均衡在多个 Gate 实例间分流。

## Migration Plan

- **实现顺序**：先实现 gRPC 重连改造（ScheduledExecutorService + 指数退避）→ 实现 WebSocket 连接数检查 → 最后实现优雅关闭编排（shutdown hook）。
- **部署**：无数据迁移；灰度发布，观察关闭时是否有连接泄漏、重连日志是否正常。
- **回滚**：保留原有重连与关闭逻辑的 fallback，或通过配置开关（如 `gate.graceful-shutdown.enabled=false`）回退到原有行为。

## Open Questions

- 是否需暴露连接数、重试次数等指标供监控？可留扩展点。
- drain 阶段「请求处理完成」的判断标准：按 Netty pipeline 的 inbound/outbound 完成，还是按业务层 ACK？建议先按「无活跃 write」+ 超时简化实现。

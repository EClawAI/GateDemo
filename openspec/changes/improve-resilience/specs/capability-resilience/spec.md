# capability-resilience (Delta)

## Purpose
提升 Gate/Game 服务韧性：优雅关闭、gRPC 指数退避重连、WebSocket 连接数上限控制。

## ADDED Requirements

### Requirement: 优雅关闭编排

系统 SHALL 通过 JVM shutdown hook 统一编排关闭顺序；SHALL 按 stop accept → drain connections → close EventLoopGroup 的顺序执行；SHALL 设置总体关闭超时（如 30s），超时后强制结束；MUST 在 drain 阶段等待已有连接处理完成或超时后再关闭底层资源。

#### Scenario: 收到 SIGTERM 时停止接受新连接
- **WHEN** 进程收到 SIGTERM 或等效关闭信号
- **THEN** 系统立即停止接受新的 WebSocket/TCP 连接
- **AND** 已建立的连接继续处理直至完成或超时

#### Scenario: drain 后关闭底层资源
- **WHEN** 所有活跃连接已 drain 完成或达到超时
- **THEN** 系统依次关闭 EventLoopGroup、gRPC Channel、Redis 客户端等资源
- **AND** 进程正常退出，无资源泄漏

### Requirement: gRPC 指数退避重连

系统 SHALL 使用 ScheduledExecutorService 进行 gRPC 重连调度；SHALL 采用指数退避策略（初始 delay → 2x → 4x → … → maxDelay）；MUST 设置最大重试次数，超限后停止重试；MUST 不再使用 `new Thread` + 固定延迟的重连方式。

#### Scenario: 重连延迟按指数增长
- **WHEN** gRPC 连接失败需要重连
- **THEN** 第一次重试在初始 delay 后执行，后续每次重试延迟为前次的 2 倍
- **AND** 延迟不超过配置的 maxDelay

#### Scenario: 达到最大重试次数后停止
- **WHEN** 重试次数达到配置的 maxRetries
- **THEN** 系统停止对该实例的自动重连
- **AND** 记录日志，可由服务发现或其他机制触发再次尝试

### Requirement: WebSocket 连接数上限

系统 SHALL 在 WebSocket handler 的 channelActive 中检查当前连接数；SHALL 支持通过配置（如 gate.ws.max-connections）设置最大连接数；当连接数超过上限时 MUST 拒绝新连接（关闭 channel 并发送或返回错误信息）。

#### Scenario: 连接数未超限时接受连接
- **WHEN** 新 WebSocket 连接建立且当前连接数小于上限
- **THEN** 系统接受该连接并正常处理
- **AND** 连接计数增加

#### Scenario: 连接数超限时拒绝连接
- **WHEN** 新 WebSocket 连接建立且当前连接数已达上限
- **THEN** 系统拒绝该连接并关闭 channel
- **AND** 向客户端返回或发送明确的错误信息（如「服务繁忙」）

### Requirement: 关闭超时可配置

系统 SHALL 支持配置优雅关闭的总超时时间（默认 30s）；SHALL 在超时后强制关闭剩余连接并释放资源；MUST 确保进程最终能退出，不被 hang 住。

#### Scenario: 配置关闭超时
- **WHEN** 管理员配置 gate.graceful-shutdown.timeout-seconds
- **THEN** 关闭流程使用该值作为 drain 与整体超时
- **AND** 超时后强制结束，确保进程退出

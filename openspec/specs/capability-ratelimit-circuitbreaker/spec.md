# capability-ratelimit-circuitbreaker Specification

## Purpose
TBD - created by archiving change integrate-ratelimit-circuitbreaker. Update Purpose after archive.
## Requirements
### Requirement: WebSocket 消息入口限流

系统 SHALL 在 WebSocket Handler 处理上行消息前执行限流；SHALL 支持 per-player（按玩家）与 global（全局）两级限流；超限时 MUST 返回约定错误码并记录日志。

#### Scenario: per-player 限流通过
- **WHEN** 玩家在时间窗口内发送的消息数未超过 per-player 限制
- **THEN** 消息正常进入业务处理
- **AND** 不返回限流错误

#### Scenario: per-player 限流超限
- **WHEN** 某玩家在时间窗口内发送的消息数超过 per-player 限制
- **THEN** 系统拒绝该消息并返回 `type: "error"` 且 `code: "RATE_LIMITED"`
- **AND** 记录限流日志（包含 playerId 或 key）

#### Scenario: global 限流超限
- **WHEN** 全局限流计数超过配置的 global 阈值
- **THEN** 系统拒绝该消息并返回 `type: "error"` 且 `code: "RATE_LIMITED"`
- **AND** 记录限流日志

### Requirement: gRPC 调用熔断

系统 SHALL 在向 Game 发送 gRPC 消息的路径上包裹 CircuitBreaker；当 CircuitBreaker 处于 OPEN 状态时 MUST 直接返回降级响应，不发起 gRPC 请求。

#### Scenario: 熔断闭合时正常调用
- **WHEN** CircuitBreaker 处于 CLOSED 状态
- **THEN** gRPC 调用正常执行
- **AND** 根据调用结果记录 success 或 failure

#### Scenario: 熔断打开时返回降级
- **WHEN** CircuitBreaker 处于 OPEN 状态
- **THEN** 系统不发起 gRPC 请求
- **AND** 返回 `type: "error"` 且 `code: "SERVICE_UNAVAILABLE"` 或 `GAME_UNAVAILABLE`

#### Scenario: 半开探测恢复
- **WHEN** CircuitBreaker 处于 HALF_OPEN 状态且 resetTimeout 已过
- **THEN** 允许少量探测请求通过
- **AND** 成功达到阈值后转 CLOSED，失败则回 OPEN

### Requirement: 限流与熔断参数可配置

系统 SHALL 支持通过配置指定限流参数（maxRequests、windowMs、per-player 与 global 阈值）及熔断参数（failureThreshold、resetTimeoutMs）；未配置时 SHALL 使用合理默认值。

#### Scenario: 配置限流参数
- **WHEN** 管理员配置 per-player 或 global 限流参数
- **THEN** 系统在启动或热加载时应用该配置
- **AND** 限流行为与配置一致

#### Scenario: 配置熔断参数
- **WHEN** 管理员配置 failureThreshold、resetTimeoutMs
- **THEN** CircuitBreaker 使用该配置进行状态转换
- **AND** 熔断触发时机与配置一致

### Requirement: 超限与熔断时记录日志

系统 SHALL 在限流触发时记录包含 key/playerId、限流类型（per-player/global）的日志；SHALL 在熔断状态变化（OPEN/HALF_OPEN/CLOSED）时记录日志。

#### Scenario: 限流日志
- **WHEN** 某次消息因限流被拒绝
- **THEN** 记录 WARN 或 INFO 级别日志
- **AND** 日志包含可追溯的 key 或 playerId

#### Scenario: 熔断状态变化日志
- **WHEN** CircuitBreaker 从 CLOSED 转为 OPEN 或 HALF_OPEN 转为 CLOSED
- **THEN** 记录状态变化日志
- **AND** 便于运维排查

### Requirement: heartbeat 可豁免限流

系统 MAY 对 type 为 heartbeat 的消息豁免 per-player 限流，以保证正常心跳不断开；SHALL 在实现中明确是否豁免及豁免策略。

#### Scenario: heartbeat 豁免
- **WHEN** 配置允许 heartbeat 豁免且消息 type 为 heartbeat
- **THEN** 该消息不占用 per-player 限流配额
- **AND** 仍受 global 限流约束（若实现需要）


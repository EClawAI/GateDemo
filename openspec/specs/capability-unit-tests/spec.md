# capability-unit-tests Specification

## Purpose
TBD - created by archiving change add-core-unit-tests. Update Purpose after archive.
## Requirements
### Requirement: gate-service 核心类单元测试

系统 SHALL 为 PlayerService、ConnectionManager、MessageDispatcher、RateLimiter、CircuitBreaker、TokenService 提供单元测试；每个类 MUST 有对应测试类，覆盖主要分支与关键异常路径。

#### Scenario: PlayerService 测试
- **WHEN** 执行 PlayerService 的玩家注册、查询、踢出等操作
- **THEN** 测试验证正确调用下游依赖并返回预期结果
- **AND** 覆盖异常情况（如玩家不存在、连接不存在）

#### Scenario: ConnectionManager 测试
- **WHEN** 执行连接建立、断开、按玩家查找
- **THEN** 测试验证连接注册与移除的正确性
- **AND** 覆盖并发场景或边界条件

#### Scenario: MessageDispatcher 测试
- **WHEN** 执行消息路由与分发
- **THEN** 测试验证消息被正确派发到对应 Handler
- **AND** 覆盖未注册类型或异常处理

#### Scenario: RateLimiter 测试
- **WHEN** 在时间窗口内请求数未超限或超限
- **THEN** 测试验证 tryAcquire 返回 true/false 符合预期
- **AND** 覆盖窗口滑动、reset 等逻辑

#### Scenario: CircuitBreaker 测试
- **WHEN** 记录成功或失败、达到 failureThreshold 或 resetTimeout
- **THEN** 测试验证状态转换为 CLOSED/OPEN/HALF_OPEN
- **AND** allowRequest 在 OPEN 时返回 false

### Requirement: game-service 核心类单元测试

系统 SHALL 为 GameMessageHandler、GameStatusService 提供单元测试；测试 MUST 覆盖消息处理与状态更新/查询的核心逻辑。

#### Scenario: GameMessageHandler 测试
- **WHEN** 传入不同类型游戏消息
- **THEN** 测试验证处理逻辑与响应格式
- **AND** 覆盖异常或未知消息类型

#### Scenario: GameStatusService 测试
- **WHEN** 执行状态更新与查询
- **THEN** 测试验证状态正确存储与返回
- **AND** 覆盖并发或边界条件

### Requirement: login/center Controller 层测试

系统 SHALL 为 login-service 与 center-service 的 HTTP Controller 提供契约或等效测试；测试 MUST 验证请求解析、业务调用与响应序列化。

#### Scenario: login Controller 测试
- **WHEN** 发送 HTTP 请求到登录接口
- **THEN** 测试验证响应结构与状态码
- **AND** 验证业务逻辑被正确调用（可通过 mock）

#### Scenario: center Controller 测试
- **WHEN** 发送 HTTP 请求到 center 接口
- **THEN** 测试验证响应结构与状态码
- **AND** 验证业务逻辑被正确调用

### Requirement: 覆盖率目标

系统 SHALL 在完成本 change 后，使 gate-service、game-service、login-service、center-service 的整体单元测试覆盖率达到 60% 以上；MUST 使用覆盖率工具（如 JaCoCo）生成报告并可在 CI 中执行。

#### Scenario: 覆盖率报告
- **WHEN** 执行 `mvn test` 或等价命令
- **THEN** 生成覆盖率报告
- **AND** 核心模块（PlayerService、ConnectionManager、MessageDispatcher、RateLimiter、CircuitBreaker、TokenService、GameMessageHandler、GameStatusService）有明确覆盖

#### Scenario: 测试独立可重复
- **WHEN** 任意单测在隔离环境运行
- **THEN** 不依赖外部服务（Redis、数据库等）即可通过
- **AND** 通过 mock 替代外部依赖

### Requirement: 测试命名与组织

系统 SHALL 为每个被测类提供对应 `{ClassName}Test` 测试类，放在 `src/test/java` 对应包下；测试方法命名 SHALL 能反映被测场景（如 `shouldReturnRateLimitedWhenExceedsLimit`）。

#### Scenario: 测试类命名
- **WHEN** 查找某类的单元测试
- **THEN** 可通过 `{ClassName}Test` 快速定位
- **AND** 测试类位于正确包路径


# capability-integration-tests-docs Specification

## Purpose
TBD - created by archiving change add-integration-tests-and-api-docs. Update Purpose after archive.
## Requirements
### Requirement: Testcontainers Redis 集成测试

系统 SHALL 使用 Testcontainers 在集成测试中启动 Redis 容器；SHALL 使被测应用连接该 Redis 实例；MUST 在测试完成后正确关闭容器；MUST 支持在具备 Docker 环境的 CI 中运行；SHALL 提供至少一个集成测试示例，验证 Redis 连接与基本读写。

#### Scenario: 集成测试使用 Redis 容器
- **WHEN** 运行集成测试
- **THEN** Testcontainers 启动 Redis 容器
- **AND** 测试使用容器暴露的 host:port 连接 Redis

#### Scenario: 无 Docker 时可选跳过
- **WHEN** 环境无 Docker 或配置跳过集成测试
- **THEN** 集成测试被跳过或标记为可选
- **AND** 单元测试等其他测试仍可执行

### Requirement: Netty EmbeddedChannel 测试

系统 SHALL 使用 Netty EmbeddedChannel 对 WebSocket 或 TCP Handler 进行测试；SHALL 能注入 Mock 依赖（如 gRPC stub、Redis 客户端）；MUST 验证消息编解码、Handler 逻辑与异常处理；SHALL 提供至少一个 EmbeddedChannel 测试用例，验证关键 Handler 行为。

#### Scenario: Handler 逻辑可被 EmbeddedChannel 验证
- **WHEN** 通过 EmbeddedChannel 写入模拟的入站消息
- **THEN** 可读取出站消息并断言内容
- **AND** 可验证异常路径（exceptionCaught）

#### Scenario: Mock 依赖支持隔离测试
- **WHEN** Handler 依赖 gRPC 或 Redis
- **THEN** 测试中注入 Mock 实现
- **AND** 不依赖真实 gRPC/Redis 服务

### Requirement: Swagger 注解与 OpenAPI 生成

系统 SHALL 在 login-service、center-service 的 REST 接口上使用 Swagger Core 注解；SHALL 通过 swagger-maven-plugin 在 Maven build 阶段生成 OpenAPI 3.0 文档；MUST 生成的文档包含接口路径、请求/响应 schema、主要错误码；MUST 支持 YAML 或 JSON 格式输出。

#### Scenario: Maven 构建生成 OpenAPI
- **WHEN** 执行 mvn package 或 mvn generate-resources
- **THEN** 在 target 或指定目录生成 OpenAPI 3.0 文档
- **AND** 文档反映当前注解的接口定义

#### Scenario: 文档包含 REST 接口契约
- **WHEN** 查看生成的 OpenAPI 文档
- **THEN** 包含 login-service、center-service 的主要 REST 路径
- **AND** 每个路径有 request body、response、可能的错误响应说明

### Requirement: 持久化存储方案规划

系统 SHALL 在文档中定义持久化存储选型（MySQL 或 PostgreSQL）；SHALL 明确 Redis 与持久化 DB 的职责划分；SHALL 描述主要数据模型（用户、角色、订单等）及表结构雏形；SHALL 说明迁移路径（如何从仅 Redis 过渡到 Redis+DB）；MUST 作为后续实现持久化的输入，本 change 不实现具体持久化代码。

#### Scenario: 选型与职责文档化
- **WHEN** 查阅持久化方案文档
- **THEN** 可明确选用 MySQL 或 PostgreSQL 及理由
- **AND** 可明确 Redis 负责缓存/会话/注册，DB 负责持久化业务数据

#### Scenario: 数据模型与迁移路径
- **WHEN** 查阅方案文档
- **THEN** 可了解主要表结构设计雏形
- **AND** 可了解从当前状态到引入 DB 的迁移步骤

### Requirement: 全链路集成测试（可选）

系统 SHALL 在条件允许时提供 WebSocket→Gate→gRPC→Game 的全链路集成测试；SHALL 使用 Testcontainers 或 Mock 降低外部依赖；若 Game 可容器化，MUST 验证端到端消息收发；若不可，SHALL 至少验证 Gate Handler + Mock Game 的集成。

#### Scenario: 全链路测试验证消息流
- **WHEN** 运行全链路集成测试
- **THEN** 从 WebSocket 或 EmbeddedChannel 发送消息
- **AND** 可验证消息经 Gate、gRPC 到达 Game 并收到响应（或 Mock 等效行为）


# capability-health-check (Delta)

## Purpose
为 Gate、Game、Login、Center 等服务提供标准健康检查能力，使容器编排与负载均衡能正确判断实例就绪状态。

## ADDED Requirements

### Requirement: gate-service 提供独立 HTTP 健康检查端点

系统 SHALL 在 gate-service 中通过 Netty 在独立端口（如 8890）暴露 HTTP 端点；SHALL 提供 `/health` 与 `/ready` 两个路径；MUST 返回 JSON 格式的健康状态，包含 Redis 连通性、gRPC 连接池状态等。

#### Scenario: 请求 /health 返回整体健康
- **WHEN** 客户端向健康端口发送 GET `/health`
- **THEN** 服务返回 200 及 JSON 体，包含 status、redis、grpc 等字段
- **AND** 当依赖不可用时，status 为 DOWN，并返回非 200 状态码

#### Scenario: 请求 /ready 表示可接受流量
- **WHEN** 客户端向健康端口发送 GET `/ready`
- **THEN** 当 Redis 与 gRPC 连接池均可用时返回 200
- **AND** 任一依赖不可用时返回 503，以便负载均衡摘除该实例

### Requirement: game-service 实现 gRPC Health Checking Protocol

系统 SHALL 在 game-service 中实现 grpc.health.v1.Health 服务；SHALL 支持 Check 与 Watch 方法；MUST 能正确响应 grpc_health_probe 或等效客户端的健康探测。

#### Scenario: Check 返回 SERVING
- **WHEN** 客户端调用 Health.Check 请求
- **THEN** 当服务就绪时返回 SERVING
- **AND** 当服务未就绪时返回 NOT_SERVING

#### Scenario: Docker 使用 grpc_health_probe 检查
- **WHEN** Docker 健康检查执行 grpc_health_probe -addr=:50051
- **THEN** 探测成功则容器标记为 healthy
- **AND** 探测失败则容器标记为 unhealthy

### Requirement: login-service 与 center-service 提供 /health 端点

系统 SHALL 在 login-service 与 center-service 的现有 HTTP 框架中添加 GET `/health` 端点；SHALL 包含 Redis 连通性与服务就绪状态；MUST 返回 JSON 格式。

#### Scenario: 健康端点可访问
- **WHEN** 客户端向 login/center 的 HTTP 端口发送 GET `/health`
- **THEN** 返回 200 及 JSON 健康状态
- **AND** Redis 不可用时状态为 DOWN，可返回 503

### Requirement: Docker 健康检查配置正确

系统 SHALL 在 docker-compose.yml 中为各服务配置与实际能力一致的健康检查命令；MUST 使用 curl 针对 HTTP 端口、grpc_health_probe 针对 gRPC 端口；SHALL 配置合理的 interval、timeout、retries。

#### Scenario: gate-service 健康检查
- **WHEN** Docker 执行 gate-service 的 healthcheck
- **THEN** 使用 curl 请求 gate 健康端口（如 8890）的 /health
- **AND** 命令成功则容器标记为 healthy

#### Scenario: game-service 健康检查
- **WHEN** Docker 执行 game-service 的 healthcheck
- **THEN** 使用 grpc_health_probe 探测 gRPC 端口
- **AND** 不再使用 curl 做 HTTP 健康检查

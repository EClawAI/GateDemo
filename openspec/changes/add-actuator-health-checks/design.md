## Context

- **当前状态**：Gate、Game、Login、Center 共 5 个服务均无标准健康检查端点，容器编排（Docker/Kubernetes）和负载均衡无法判断实例是否就绪。gate-service 基于 Netty WebSocket，无 HTTP 健康接口；game-service 仅暴露 gRPC 端口；login-service/center-service 使用 HTTP 框架但未提供 `/health`。docker-compose.yml 中 game-service 使用 `curl` 做 HTTP 健康检查，但 game-service 无 HTTP 服务，导致健康检查始终失败。
- **问题**：无健康检查导致容器重启、滚动更新时无法正确判断就绪状态；负载均衡可能将流量导向未就绪实例；运维无法快速诊断服务故障。
- **约束**：不使用 Spring Boot Actuator 或 Spring 依赖；gate-service 需在 Netty 层面单独暴露轻量 HTTP 健康端口；game-service 需实现标准 gRPC 健康检查协议。

## Goals / Non-Goals

**Goals:**
- 所有服务具备可被容器编排调用的健康检查端点
- gate-service 通过 Netty 在独立端口（如 8890）提供 `/health`、`/ready`
- game-service 实现 gRPC Health Checking Protocol（grpc.health.v1.Health）
- login-service / center-service 在现有 HTTP 框架中添加 `/health`
- docker-compose.yml 中健康检查命令与各服务实际能力一致

**Non-Goals:**
- 不实现完整的指标监控或 Metrics 端点
- 不引入 Spring Boot Actuator 或其他 Spring 健康检查组件
- 不改变现有业务端口或协议

## Decisions

1. **gate-service 使用独立 HTTP 健康端口**
   - 在 Netty 中单独启动一个轻量 HTTP 服务器（如基于 HttpServerCodec），监听 8890 端口，提供 GET `/health`、`/ready`，返回 JSON。
   - **理由**：WebSocket 端口与健康检查职责分离，避免在 WebSocket 管道中混入 HTTP；独立端口便于防火墙与负载均衡单独配置。

2. **game-service 实现 gRPC Health Protocol**
   - 使用 grpc-java 内建的 `io.grpc.health.v1.HealthGrpc`，实现 `Check` 和 `Watch` 接口；Docker 使用 `grpc_health_probe` 或等效工具做健康检查。
   - **理由**：符合 gRPC 生态标准，可与 K8s、Consul 等编排/服务发现工具直接集成。

3. **健康检查内容**
   - `/health`（或 gRPC Health Check）：包含 Redis 连通性、gRPC 连接池状态（gate 侧）、服务就绪状态；返回 JSON 格式如 `{"status":"UP","redis":"UP","grpc":"UP"}`。
   - `/ready`（gate）：表示可接受流量，当 Redis 与 gRPC 连接池均可用时为 UP。
   - **理由**：区分存活与就绪，便于滚动更新时正确摘除/挂载流量。

4. **Docker 健康检查命令**
   - gate-service：`curl -f http://localhost:8890/health` 或等效
   - game-service：`grpc_health_probe -addr=:50051` 或 `-addr=localhost:50051`
   - login/center：`curl -f http://localhost:8080/health`（或实际端口）
   - **理由**：与实际暴露的端点一致，避免误判。

## Risks / Trade-offs

- **[风险]** 新增健康端口增加攻击面 → 健康端口仅在内网或管理网暴露，不对外；可配置绑定地址。
- **[权衡]** 健康检查逻辑与业务耦合 → 健康接口仅做轻量探测（Redis PING、连接池是否可用），不执行重业务逻辑；超时短（如 3s）。
- **[风险]** grpc_health_probe 需单独安装 → 在 Dockerfile 中 COPY 或下载 grpc_health_probe 二进制，或在镜像构建阶段包含；文档说明安装方式。

## Migration Plan

- **实现顺序**：先实现 gate-service Netty 健康端口 → game-service gRPC Health → login/center `/health` → 更新 docker-compose.yml 健康检查命令。
- **部署**：健康检查为新增能力，不影响现有流量；部署后观察健康检查通过率与延迟。
- **回滚**：保留原有端口不变；若健康检查导致问题，可临时移除或调整 docker-compose 中的 healthcheck 配置，业务不受影响。

## Open Questions

- 健康端口（如 8890）是否需可配置？建议支持 `gate.health.port` 等配置项。
- 若 Redis 或 gRPC 连接池不可用，`/ready` 是否返回 503？建议是，以便负载均衡摘除该实例。

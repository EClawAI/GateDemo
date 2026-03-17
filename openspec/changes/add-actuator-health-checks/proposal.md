## Why

当前存在以下问题：
- 所有服务均无标准健康检查端点，容器编排和负载均衡无法判断实例是否就绪
- docker-compose.yml 中 game-service 使用 curl HTTP 健康检查，但 game-service 实际只暴露 gRPC 端口，健康检查始终失败

## What Changes

为所有服务添加自定义健康检查端点并修复 Docker 健康检查：
- gate-service 通过 Netty 暴露轻量 HTTP 健康检查端口（独立于 WebSocket 端口）
- game-service 实现 gRPC Health Checking Protocol（`grpc.health.v1.Health`）
- login-service / center-service 在现有 HTTP 框架中添加 `/health` 端点
- 修复 docker-compose.yml 健康检查命令

## 核心功能

1. **gate-service 健康检查 HTTP 端点**
   - 使用 Netty 在独立端口（如 8890）提供 `/health`、`/ready` 端点
   - 返回 JSON 格式的健康状态（Redis 连通性、gRPC 连接池状态等）

2. **game-service gRPC Health Check**
   - 实现 gRPC Health Checking Protocol
   - Docker 使用 `grpc_health_probe` 替代 curl 做健康检查

3. **REST 服务健康检查**
   - login-service / center-service 添加 `/health` 端点
   - 包含 Redis 连通性、服务就绪状态

4. **Docker 健康检查修复**
   - game-service 改为使用 `grpc_health_probe` 或自定义健康端口
   - 健康检查命令与实际暴露端口一致

## Impact

- 影响 `gate-service` 新增轻量 HTTP 健康检查服务
- 影响 `game-service` 实现 gRPC Health Checking Protocol
- 影响 `login-service` / `center-service` 添加 `/health` 端点
- 影响 `docker-compose.yml` 健康检查配置

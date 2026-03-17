# add-actuator-health-checks 任务清单

## 1. gate-service Netty 健康检查服务

- [x] 1.1 在 gate-service 中新增 HealthCheckHttpServer，使用 Netty HttpServerCodec 在独立端口（默认 8890）启动轻量 HTTP 服务
- [x] 1.2 实现 GET `/health` 与 GET `/ready` 路由，聚合 Redis PING、GameGrpcClientPool 连接池状态，返回 JSON
- [x] 1.3 在 Gate 主启动流程中初始化 HealthCheckHttpServer，并在 @PreDestroy 中关闭
- [x] 1.4 添加 gate.health.port 等配置项，支持健康端口可配置

## 2. game-service gRPC Health Protocol

- [x] 2.1 引入 grpc-services 依赖，使用 HealthStatusManager
- [x] 2.2 实现 Check 与 Watch 方法，根据服务状态返回 SERVING 或 NOT_SERVING
- [x] 2.3 在 game-service gRPC Server 注册 Health 服务
- [x] 2.4 在 docker-compose 中配置 grpc_health_probe 用于健康检查

## 3. login-service 与 center-service /health 端点

- [x] 3.1 在 login-service HTTP 框架中新增 GET `/health` 路由，检查 Redis 连通性并返回 JSON
- [x] 3.2 在 center-service HTTP 框架中新增 GET `/health` 路由，返回 JSON
- [x] 3.3 统一健康响应格式（如 {"status":"UP","components":{...}}），依赖不可用时返回 503

## 4. docker-compose 健康检查修复

- [x] 4.1 为 gate-service 添加 healthcheck，使用 curl 请求健康端口 /health，配置 interval/timeout/retries
- [x] 4.2 将 game-service 的 healthcheck 改为 grpc_health_probe，移除原有的 curl HTTP 检查
- [x] 4.3 更新 game-service 端口映射和环境变量对齐 gRPC 架构
- [x] 4.4 确保各服务健康检查命令的端口与实际情况一致

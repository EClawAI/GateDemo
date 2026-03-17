## Context

- **当前状态**：项目已从 HTTP 直连架构迁移至 gRPC + Redis 架构，但部署配置未同步更新。docker-compose.yml 中 Gate 容器仍使用 GAME_HOST/GAME_PORT 等 HTTP 环境变量，而 Gate 代码已改为通过 gRPC 连接 Game；README.md 仍描述"无 Redis 版本、HTTP 直连"等过时内容；docker-compose 中缺少 login-service、center-service 的容器定义，无法一键启动完整五服务栈。
- **问题**：环境变量与代码不匹配导致 Gate 无法正确连接 Game；文档误导开发者；缺少 login/center 容器导致本地开发与联调困难。
- **约束**：保持与现有代码的兼容；不改变服务间通信协议；README 需准确反映当前架构。

## Goals / Non-Goals

**Goals:**
- 更新 docker-compose.yml 环境变量，Gate 使用 GAME_GRPC_HOST、GAME_GRPC_PORT 等 gRPC 相关变量
- 补充 login-service、center-service 的容器定义，完善服务间依赖与网络
- 重写 README.md，准确描述 gRPC + Redis + 5 服务架构、启动步骤与依赖说明

**Non-Goals:**
- 不在此 change 中实现 K8s 或云原生部署
- 不改变各服务的业务逻辑或端口约定
- 不引入新的编排工具（如 Helm）

## Decisions

1. **环境变量命名规范**
   - Gate 连接 Game：GAME_GRPC_HOST、GAME_GRPC_PORT（或 gate.games 配置中的等效项）
   - 移除 GAME_HOST、GAME_PORT 等 HTTP 时代变量
   - **理由**：与 gate-service 中 GateConfig、GameGrpcClientPool 的读取逻辑一致，避免歧义。

2. **五服务容器定义**
   - 在 docker-compose 中定义：redis、gate-service、game-service、login-service、center-service
   - 服务依赖：gate 依赖 redis、game；login/center 依赖 redis；game 可单独或与 gate 同网
   - **理由**：一键启动完整栈，便于本地开发与集成测试。

3. **README 结构**
   - 架构说明：gRPC（Gate↔Game）、Redis（会话/发现）、五服务职责
   - 启动方式：docker-compose up、各服务端口与健康检查
   - 移除：无 Redis 版本、HTTP 直连等过时描述
   - **理由**：新开发者可快速理解架构并正确启动。

4. **网络与端口**
   - 各服务使用独立端口，docker-compose 中通过 service 名作为 hostname 互通（如 gate-service 连接 game-service:50051）
   - **理由**：符合 Docker Compose 默认网络模型，无需复杂 DNS 配置。

## Risks / Trade-offs

- **[风险]** 现有用户依赖旧环境变量 → 在 README 中明确迁移说明，标注 GAME_GRPC_HOST 替代 GAME_HOST；必要时在代码中做短暂兼容读取。
- **[权衡]** README 篇幅增加 → 保持结构清晰，用章节划分；可将详细配置放到 CONFIG.md 或 docs 目录。

## Migration Plan

- **实现顺序**：先更新 docker-compose.yml 环境变量与容器定义 → 本地验证五服务可正常启动与互通 → 重写 README.md。
- **部署**：无数据迁移；本地/CI 使用 docker-compose 时需拉取最新配置；已部署环境需按新变量更新。
- **回滚**：恢复旧 docker-compose 与 README 到 Git 历史即可；若代码已依赖新变量，需同步回滚代码。

## Open Questions

- login-service、center-service 的默认端口是否与 gate、game 冲突？需核对各服务 application.yml 或等效配置。
- 是否需要 docker-compose.override.yml 示例用于开发调试（如挂载本地代码）？

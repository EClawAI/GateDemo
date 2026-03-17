# capability-deployment-config (Delta)

## Purpose
将部署配置（docker-compose、README）与当前 gRPC + Redis 五服务架构对齐，支持一键启动完整服务栈。

## ADDED Requirements

### Requirement: docker-compose 环境变量与 gRPC 架构一致

系统 SHALL 在 docker-compose.yml 中为 Gate 容器配置 gRPC 相关环境变量；MUST 使用 GAME_GRPC_HOST、GAME_GRPC_PORT（或与 gate 配置键对应的等效变量）；SHALL 移除或替换 GAME_HOST、GAME_PORT 等 HTTP 时代变量。

#### Scenario: Gate 使用 gRPC 变量连接 Game
- **WHEN** 通过 docker-compose 启动 Gate 与 Game
- **THEN** Gate 容器通过 GAME_GRPC_HOST、GAME_GRPC_PORT 正确连接 Game 的 gRPC 端口
- **AND** Gate 能成功建立 gRPC 连接并完成业务调用

#### Scenario: 各服务端口与主机名正确
- **WHEN** 各服务在 docker-compose 网络中启动
- **THEN** 服务间通过 service 名（如 game-service:50051）互通
- **AND** 环境变量中的主机与端口与实际情况一致

### Requirement: docker-compose 包含五服务容器定义

系统 SHALL 在 docker-compose.yml 中定义 redis、gate-service、game-service、login-service、center-service 五个（或以上）服务；SHALL 配置服务间依赖关系（depends_on）与网络；MUST 使开发者可通过 docker-compose up 一键启动完整栈。

#### Scenario: 一键启动五服务
- **WHEN** 执行 docker-compose up -d
- **THEN** redis、gate、game、login、center 均成功启动
- **AND** 各服务能按依赖顺序启动，网络互通

#### Scenario: login-service 与 center-service 容器存在
- **WHEN** 查看 docker-compose 服务列表
- **THEN** login-service 与 center-service 均有完整定义（镜像、端口、环境变量、健康检查等）
- **AND** 可单独或与其它服务一起启动

### Requirement: README 准确描述架构与启动方式

系统 SHALL 在 README.md 中描述当前 gRPC + Redis + 五服务架构；SHALL 说明各服务职责、端口与依赖；SHALL 提供 docker-compose 启动步骤；MUST 移除"无 Redis 版本"、"HTTP 直连"等过时描述。

#### Scenario: 架构说明准确
- **WHEN** 开发者阅读 README 架构章节
- **THEN** 能理解 Gate 与 Game 通过 gRPC 通信、Redis 用于会话/发现
- **AND** 无与当前实现矛盾的 HTTP 直连或"无 Redis"描述

#### Scenario: 启动步骤可执行
- **WHEN** 开发者按 README 启动步骤操作
- **THEN** 能成功启动完整五服务栈
- **AND** 各服务健康检查通过，可正常访问

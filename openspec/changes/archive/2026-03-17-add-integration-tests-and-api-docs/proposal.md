## Why

当前存在以下缺口：
- 无集成测试，无法验证 WebSocket→Gate→gRPC→Game 全链路
- REST 接口无 API 文档，调用方需阅读源码才能了解接口契约
- 仅使用 Redis 作为缓存/注册，无持久化存储方案规划

## What Changes

建立集成测试、API 文档与持久化规划：
- 使用 Testcontainers(Redis) + Netty EmbeddedChannel 编写集成测试
- 使用 Swagger Core 注解 + swagger-maven-plugin 生成 OpenAPI 文档
- 定义持久化存储方案（选型、迁移路径）

## 核心功能

1. **Testcontainers 集成测试**
   - Redis 容器化测试环境
   - Netty EmbeddedChannel 模拟 WebSocket 通信
   - 全链路集成测试用例

2. **OpenAPI/Swagger API 文档**
   - 使用 Swagger Core 注解标注 REST 接口
   - 通过 Maven 插件生成 OpenAPI 3.0 文档
   - 生成静态 HTML 文档或接入 Swagger UI（独立部署）

3. **持久化存储方案规划**
   - 选型（MySQL/PostgreSQL 等）
   - 数据模型设计与迁移计划
   - Redis 与持久化存储职责划分

## Impact

- 影响 gate-service（集成测试）
- 影响 game-service（集成测试）
- 影响 login-service（API 文档注解、集成测试）
- 影响 center-service（API 文档注解、集成测试）

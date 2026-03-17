# 集成测试与 API 文档 (design.md)

## Context

- **当前状态**：无集成测试，无法自动化验证 WebSocket→Gate→gRPC→Game 全链路；REST 接口（login-service、center-service）无 API 文档，调用方需阅读源码；仅使用 Redis 作为缓存/注册，无持久化存储方案规划。
- **问题**：回归依赖人工；新接入方难以快速了解 REST 契约；持久化选型与职责划分未明确，影响后续扩展。
- **约束**：使用 Testcontainers、Netty EmbeddedChannel、Swagger Core、swagger-maven-plugin 等；不引入 Spring Boot Test 或 Spring 专属测试设施；持久化方案为规划与选型，非本 change 实现。

## Goals / Non-Goals

**Goals:**
- 使用 Testcontainers(Redis) + Netty EmbeddedChannel 编写集成测试
- 使用 Swagger Core 注解 + swagger-maven-plugin 生成 OpenAPI 3.0 文档（login-service、center-service REST APIs）
- 定义持久化存储方案（MySQL/PostgreSQL 选型、Redis 与 DB 职责划分）

**Non-Goals:**
- 不实现完整的持久化存储与迁移
- 不引入 Spring Boot 或 Spring Test
- 不在此 change 中实现 Swagger UI 服务端（可生成静态文档或独立部署）

## Decisions

1. **Testcontainers 集成 Redis**
   - 在集成测试中通过 Testcontainers 启动 Redis 容器；测试启动时拉取/启动 Redis，测试结束关闭；应用配置使用容器映射的 host:port 连接 Redis。
   - **理由**：与真实 Redis 行为一致；无需预置 Redis 环境；适合 CI 流水线。

2. **Netty EmbeddedChannel 模拟 WebSocket**
   - 使用 Netty 的 EmbeddedChannel 对 WebSocket/TCP Handler 进行单元或集成测试；可注入 Mock 的 gRPC stub 或真实连接；验证消息编解码与 Handler 逻辑。
   - **理由**：无需启动完整 Gate 进程；测试聚焦 Handler 层；与 Spring 解耦。

3. **Swagger Core 注解 + Maven 插件**
   - 在 login-service、center-service 的 REST 接口上使用 Swagger Core 注解（如 @Operation、@ApiResponse）；通过 swagger-maven-plugin 在 build 阶段生成 OpenAPI 3.0 YAML/JSON；可输出为静态文件或接入 Swagger UI。
   - **理由**：与代码同源，减少文档与实现漂移；Maven 生态成熟；不依赖 Spring Boot。

4. **持久化方案规划**
   - 选型：MySQL 或 PostgreSQL，根据团队熟悉度与生态选择；数据模型：用户账号、角色、订单等需持久化的业务数据；Redis 与 DB 职责：Redis 负责缓存、会话、服务发现、临时状态；DB 负责持久化业务数据；迁移路径：先规划表结构，后续 change 实现 JPA/JDBC 等。
   - **理由**：为后续实现提供明确指导；避免 Redis 滥用导致的数据丢失风险。

5. **集成测试范围**
   - 覆盖：Gate Handler + Redis、login/center REST + Redis、可选 Gate→Game 全链路（需 Game 可容器化或 Mock）；不覆盖：端到端压测、混沌测试。
   - **理由**：先建立基础自动化验证；复杂场景可后续补充。

## Risks / Trade-offs

- **[风险]** Testcontainers 需要 Docker → 在 CI 环境中确保 Docker 可用；本地开发需安装 Docker；可提供「无 Docker 时跳过集成测试」的开关。
- **[风险]** Swagger 注解增加代码噪音 → 仅对必要的 API 添加；使用最小注解集；考虑后续用反射+约定减少注解。
- **[权衡]** 持久化方案仅为文档 → 本 change 不落库实现，但需与团队达成共识，以便后续 change 按方案执行。

## Migration Plan

- **实现顺序**：先定义持久化方案文档 → 添加 Testcontainers 依赖与 Redis 集成测试示例 → 为 login/center 添加 Swagger 注解并配置 Maven 插件生成 OpenAPI → 补充 Netty EmbeddedChannel 测试用例。
- **部署**：无运行时影响；CI 需支持 Docker；生成的 OpenAPI 文档可发布到文档站点或纳入构建产物。
- **回滚**：移除 Testcontainers 依赖或跳过集成测试可回退；Swagger 注解可保留不影响运行。

## Open Questions

- MySQL 与 PostgreSQL 的最终选型？建议根据团队经验与部署环境（云厂商托管支持）决定。
- OpenAPI 文档的发布方式？静态 HTML、Swagger UI 独立服务、或纳入现有文档站点。
- 全链路集成测试是否在本 change 中实现？若 Game 未容器化，可先用 Mock gRPC 验证 Gate 逻辑。

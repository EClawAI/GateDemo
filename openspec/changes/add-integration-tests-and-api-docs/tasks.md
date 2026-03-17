# P016: 集成测试与 API 文档

## 1. Testcontainers + Redis 集成测试

- [ ] 1.1 引入 Testcontainers 依赖，在集成测试中启动 Redis 容器
- [ ] 1.2 测试启动时拉取/启动 Redis，测试结束关闭；应用配置使用容器映射的 host:port
- [ ] 1.3 编写 Gate + Redis 集成测试：连接建立、消息持久化、会话缓存等
- [ ] 1.4 编写 login/center + Redis 集成测试；提供「无 Docker 时跳过」的开关

## 2. Netty EmbeddedChannel 测试

- [ ] 2.1 使用 Netty EmbeddedChannel 对 WebSocket/TCP Handler 进行测试
- [ ] 2.2 注入 Mock 的 gRPC stub 或真实连接，验证消息编解码
- [ ] 2.3 覆盖 Handler 层消息入站、出站及异常路径
- [ ] 2.4 可选：Gate→Game 全链路测试（Game 可容器化或 Mock 时）

## 3. Swagger 与 OpenAPI 文档

- [ ] 3.1 在 login-service、center-service 的 REST 接口上添加 Swagger Core 注解（@Operation、@ApiResponse）
- [ ] 3.2 配置 swagger-maven-plugin，在 build 阶段生成 OpenAPI 3.0 YAML/JSON
- [ ] 3.3 将生成的文档输出为静态文件，纳入构建产物
- [ ] 3.4 文档说明如何发布到文档站点或接入 Swagger UI

## 4. 持久化存储规划文档

- [ ] 4.1 编写持久化方案文档：MySQL 与 PostgreSQL 选型对比
- [ ] 4.2 定义数据模型（用户账号、角色、订单等需持久化的业务数据）
- [ ] 4.3 明确 Redis 与 DB 职责：Redis 负责缓存、会话、服务发现、临时状态；DB 负责持久化业务数据
- [ ] 4.4 规划迁移路径：表结构设计，后续 change 实现 JPA/JDBC 等

# 核心单元测试 (add-core-unit-tests)

## Context

- **当前状态**：整体测试覆盖率约 5%，gate-service 仅有少量测试；game-service、login-service、center-service 测试更少。核心路径如 PlayerService、ConnectionManager、MessageDispatcher、RateLimiter、CircuitBreaker、TokenService、GameMessageHandler、GameStatusService 等缺乏单元测试，重构或修复易引入回归。
- **问题**：覆盖率远低于生产标准（一般要求 >60%），无法安全地进行代码演进。
- **约束**：使用 JUnit 5 与 Mockito（或等价测试框架）；不依赖 Spring Boot 注解进行测试；测试应尽量快、不启动完整服务；可 mock 外部依赖（Redis、gRPC、Netty Channel）。

## Goals / Non-Goals

**Goals:**

- 为 gate-service 核心类补充单元测试：PlayerService、ConnectionManager、MessageDispatcher、RateLimiter、CircuitBreaker、TokenService。
- 为 game-service 核心类补充单元测试：GameMessageHandler、GameStatusService。
- 为 login-service、center-service 的 Controller 层（HTTP 接口）补充契约测试或等效测试。
- 目标：整体覆盖率从约 5% 提升到 60%+。

**Non-Goals:**

- 不在此 change 中实现完整集成测试（WebSocket→Gate→gRPC→Game 全链路）。
- 不强制达到 80% 以上覆盖率；以核心路径为主。
- 不改变被测类的公共 API 以迁就测试（可适当增加可测试性，如注入依赖）。

## Decisions

1. **测试框架：JUnit 5 + Mockito**
   - 单元测试使用 JUnit 5 与 Mockito 进行 mock 与断言；断言可配合 AssertJ 提升可读性。
   - **理由**：主流选择，与现有 Java 生态兼容；无 Spring 依赖时更轻量。

2. **外部依赖一律 mock**
   - Redis、gRPC Client、Netty Channel、ObjectMapper 等通过构造函数或 setter 注入时，测试中传入 mock 对象。
   - **理由**：单元测试应独立、快速、不依赖外部服务；避免 flaky。

3. **Controller 层测试策略**
   - login/center 的 HTTP 接口：使用内嵌 HTTP 服务器（如 JUnit 的 @BeforeEach 启动或手动构造）或直接调用 Controller 方法传入 mock Request/Response；验证请求解析、业务调用、响应序列化。
   - **理由**：不依赖 Spring Boot 时，可用轻量 HTTP 测试工具或直接测 Controller 逻辑。

4. **测试组织**
   - 每个被测类对应一个测试类，命名：`{ClassName}Test`，放在 `src/test/java` 对应包下。
   - **理由**：便于查找与维护；与 Maven/Gradle 默认约定一致。

5. **覆盖率目标分级**
   - PlayerService、ConnectionManager、MessageDispatcher、TokenService：覆盖主要分支与异常路径。
   - RateLimiter、CircuitBreaker：覆盖状态转换、边界条件。
   - GameMessageHandler、GameStatusService：覆盖核心处理逻辑。
   - **理由**：优先保障高风险、高变化模块。

## Risks / Trade-offs

- **[风险]** 过度 mock 导致测试与实现耦合 → mitigation：尽量测行为（输出、副作用）而非实现细节；避免对私有方法直接测。
- **[权衡]** Controller 无 Spring 时测试方式不同 → 采用直接调用或轻量 HTTP 客户端，确保请求/响应契约正确即可。
- **[风险]** 覆盖率数字达标但断言不足 → 每个测试需有明确断言，避免空测试。

## Migration Plan

- **实现顺序**：1) gate-service 核心类；2) game-service 核心类；3) login/center Controller；4) 运行覆盖率报告并查漏补缺。
- **部署**：测试仅影响 CI 与本地开发，无需生产部署。
- **回滚**：删除或跳过新增测试即可；无运行时影响。

## Open Questions

- 是否引入 JaCoCo 或类似工具生成覆盖率报告？建议是，便于追踪 60% 目标。
- TokenService 若依赖外部 Redis，是否在本 change 中实现？若未实现 TokenService，可先测接口与 mock 实现。

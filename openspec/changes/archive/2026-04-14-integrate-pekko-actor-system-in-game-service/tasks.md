# Tasks: integrate-pekko-actor-system-in-game-service

## 1. 依赖

- [x] 1.1 在 `game-service/pom.xml` 添加 `org.apache.pekko:pekko-actor-typed_2.13`（无版本号）
- [x] 1.2 在 `game-service/pom.xml` 添加 **`pekko-actor-testkit-typed_2.13`**（Typed TestKit；BOM 中托管名），`scope` 为 `test`

## 2. 配置与 Bean

- [x] 2.1 新增 `game-service/src/main/resources/application.conf`（HOCON），含 `pekko` 根配置与系统名（如 `game`）
- [x] 2.2 新增 `@Configuration` 类，定义 **`ActorSystem` 单例 Bean**（从 classpath 加载配置并创建 Typed `ActorSystem`）
- [x] 2.3 实现 **`ActorSystem` 有序停机**（如 `DisposableBean` / `@PreDestroy`：`terminate()` + await / 超时），并在代码注释中说明与 `GameServiceApplication` 关闭钩子的关系

## 3. 最小验证与测试

- [x] 3.1 新增单元测试：使用 **`ActorTestKit`** 或 **`@SpringBootTest`**，spawn **父 / 子** Typed Actor，完成 **`tell`（或 `ask`）往返**
- [x] 3.2 本地执行 `mvn -pl game-service test` 通过；全仓库 `mvn -q -DskipTests compile` 通过

## 4. 文档

- [x] 4.1 在 `docs/pekko-game-actor-openspec-roadmap.md` 阶段 1 表格中为 **`integrate-pekko-actor-system-in-game-service`** 增加指向本 change `proposal.md` 的链接（与阶段 0 风格一致）

# Design: Integrate Pekko ActorSystem in game-service

## Context

- `game-service` 使用 **Spring Boot**；入口 [`GameServiceApplication`](../../../game-service/src/main/java/com/clawai/gatedemo/game/GameServiceApplication.java) 在 `CommandLineRunner.run` 中 **`latch.await()`** 阻塞主线程，并用 **`Runtime.addShutdownHook`** 在收到信号时 **`latch.countDown()`**。
- 父 POM 已 **import `pekko-bom_2.13`**，JDK **21**；规格见 [`pekko-game-baseline`](../../../openspec/specs/pekko-game-baseline/spec.md)。

## Goals / Non-Goals

**Goals:**

- 提供**单一** `ActorSystem`（名称建议在 HOCON 中固定，例如 **`game`**），由 Spring 容器创建并注入。
- 进程退出前 **显式** `ActorSystem.terminate()`，并 **await** `whenTerminated`（或等效 `CompletionStage`/`Duration` 超时策略），避免邮箱与 dispatcher 泄漏。
- `application.conf` 作为 Pekko 配置源；与 Spring `application.yml` 并存（首版不强制合并为单一配置源）。
- 测试证明可 **spawn** Typed **父子** Actor 并完成 **`tell` 往返**（无业务语义亦可）。

**Non-Goals:**

- gRPC `StreamObserver` → Actor 邮箱（阶段 2）。
- 消息信封、correlationId / battleId（独立 change `define-game-actor-message-envelope`）。
- Cluster、Remote、Sharding。

## Decisions

| 决策 | 选择 | 说明 |
|------|------|------|
| `ActorSystem` 归属 | **Spring `@Bean`**，单例 | 与现有 Boot 一致，便于测试替换 |
| 配置 | **classpath `application.conf`** | Pekko 默认加载；`ActorSystem.create(config)` 或 `ActorSystem.create(build config from resource)` |
| 停机 | **`DisposableBean` 或 `@PreDestroy`** 中 `terminate()` + await | 与 Spring `ContextClosedEvent` 顺序一致；**在**自定义 **ShutdownHook 释放 latch 之前或之后**须明确：建议 **先**触发 Spring **close**（若路径上可行）以执行 `DisposableBean`，**或**在 Hook 内直接取 Bean 执行 `terminate`（二选一写死，见 Risks） |
| 最小 Actor | **测试内**或**启动时** spawn 占位 `Behavior` | 路线图验收：父子 `tell`；避免在 gRPC 线程创建 Actor |
| Testkit | **`pekko-actor-testkit_2.13` test scope** | 单测不强制起 Spring；集成测试可选 `@SpringBootTest` |

**与 `CountDownLatch` 的协调（推荐写入实现时的注释）：**

1. **优先**：将 **`ActorSystem` 停止** 挂到 **Spring 生命周期**（`DisposableBean.destroy`），确保 **SIGTERM** 走 Spring 关闭时先停 Actor。
2. 若当前部署 **仅**依赖 **ShutdownHook** 而 **不**关闭 Context，则 Hook 内 **须**从 **已启动的** `ApplicationContext` 取得 `ActorSystem` 并 `terminate()` + await（需注意 **静态引用 / `SpringContextHolder`** 的取用方式与顺序）。

具体采用 **(1)** 还是 **(2)** 在实现阶段以**最小改动**为准；若 **(1)**，需验证 **`spring.lifecycle.timeout-per-shutdown-phase`** 是否足够等待 Pekko 终止。

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| Hook 与 Spring 关闭顺序不确定 | 在上述二选一方案中择一并写入类注释；本地 **`kill`** 与 **Ctrl+C** 各测一次 |
| `terminate` 阻塞过久 | `Duration` 超时 + 日志告警；与 K8s `terminationGracePeriodSeconds` 对齐文档说明 |
| JVM 堆外 / 线程数上升 | 阶段 7 再调优；本阶段仅 **INFO** 记录 `ActorSystem` 名 |

## Migration Plan

1. 合并后本地 **`mvn -pl game-service test`** 通过。
2. 部署无配置变更要求（HOCON 随 JAR）；若需覆盖，后续可加环境特定 `application.conf` 片段。

## Open Questions

- 是否在首版启用 **CoordinatedShutdown** 与 Spring **并列阶段**：可推迟到观测/集群前。
- `GameServiceApplication` 是否应 **`SpringApplication.exit`** / **`context.close()`** 与 latch 联动：**可单列小 refactor**，非本变更必须项。

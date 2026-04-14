# Design: Pekko Game baseline and BOM

## Context

- 仓库为 **Maven 多模块**，父 POM 使用 **Spring Boot 3.2.x**，`game-service` 已使用 **gRPC Java** 与 **`grpc-netty-shaded`**（Netty 位于 **relocate 后的包**），与路线图 [阶段 0](../../../docs/pekko-game-actor-openspec-roadmap.md) 一致。
- 目标是在 **不实现 ActorSystem** 的前提下，完成 **JDK + Pekko BOM + 模块顺序** 的决策冻结，供后续 `integrate-pekko-actor-system-in-game-service` 等变更引用。

## Goals / Non-Goals

**Goals:**

- 父 POM 通过 **`org.apache.pekko:pekko-bom_2.13`** 导入 **Pekko 1.5.0**（当前 **1.x 稳定** 线；升级策略见下文）。
- 父 POM 将 **`java.version`**（及 compiler **source/target**）设为 **21**，与团队运行/CI 一致。
- 文档化 **Pekko 可选模块引入顺序**：先 **`pekko-actor-typed`** → 需要时再 **`pekko-stream`** → **Remote/Cluster** 仅在有单独变更与运维前提时引入。
- 按进程记录 **Netty/gRPC 并存** 的风险表述（见 Risks）。

**Non-Goals:**

- 不在本变更中向 `game-service` **添加** `pekko-actor-typed` 等实现依赖（除非 `tasks.md` 明确将「仅 BOM、零传递依赖」改为「预声明 typed 无代码」；默认前者）。
- 不引入 **Pekko Cluster / Sharding**、**pekko-remote**。
- 不使用 **Pekko 2.0.x milestone**（如 `2.0.0-M1`）作为生产依赖（[官方说明：2.0 milestone 非生产用途](https://pekko.apache.org/version-support.html)）。

## Decisions

| 决策 | 选择 | 说明 |
|------|------|------|
| Pekko 版本线 | **1.5.0**（`pekko-bom_2.13`） | 与 [Version Support](https://pekko.apache.org/version-support.html)「优先使用最新 1.x」一致；后续同系列补丁升级经简短评审即可。 |
| Scala 二进制后缀 | **`_2.13`** | 全仓库 Pekko 构件统一 `_2.13`，与 Java API 示例生态一致；若未来统一改 `_3`，需单独变更。 |
| JDK | **21** | 与团队现状一致；Spring Boot 3.2 支持 Java 21。 |
| BOM 放置 | 父 POM `dependencyManagement` **import** `pekko-bom_2.13` | 子模块声明 `pekko-actor-typed_2.13` 等时不写版本号。 |
| 首套运行时 API | 后续 change 使用 **Typed**（`pekko-actor-typed`） | 与路线图阶段 1 一致；Classic 不在基线讨论范围。 |

**备选未采纳：** 仅锁 **1.1.x** — 可维护但缺少新补丁；**1.5.0** 作为新基线更简单。

## Risks / Trade-offs

| 风险 | 缓解 |
|------|------|
| 父 POM 升到 Java 21 导致 **本地/旧 CI** 构建失败 | 在 `tasks.md` 中验收：文档与 CI 镜像声明 JDK 21；发布说明中标注 **BREAKING**。 |
| **gRPC shaded Netty** 与 **`io.netty.*`**（如 gate 使用 `netty-all`）及未来 **Pekko Remote** 引入的 Netty **多线并存** | `game-service`：shaded gRPC + 单机 Typed 阶段 **冲突风险相对低**；升级依赖时执行 **`mvn dependency:tree`**。`gate-service` / `player-client`：区分 gRPC 栈与原生 Netty 栈，关注 **`io.netty` 版本唯一性**。 |
| 多套 EventLoop / 堆外缓冲导致 **内存与 CPU 基线** 上升 | 性能与线程模型在路线图阶段 2/7 处理；基线仅记录不掩盖。 |
| BOM 与 **gRPC** 独立升级导致传递依赖变化 | 升级 Pekko 或 gRPC 的 PR **必须**附带相关模块 `dependency:tree` 片段。 |

## Migration Plan

1. 将开发者与 CI **JDK** 切至 **21**。
2. 合并本变更：父 POM **Java 21** + **Pekko BOM import**。
3. 全仓库 `mvn -q verify`（或团队标准命令）通过。
4. 后续 change 再在 `game-service` 增加 `pekko-actor-typed_2.13`（无版本号）并实现 `ActorSystem`。

**回滚：** 还原父 POM 中 Java 与 BOM 相关 edits；无业务代码时回滚风险低。

## Open Questions

- **`pekko-actor-testkit_2.13`** 是否在父 POM **test** 范围的 `dependencyManagement` 中预声明：可在阶段 1 首次写 Actor 测试时再引入，避免基线 scope 膨胀。

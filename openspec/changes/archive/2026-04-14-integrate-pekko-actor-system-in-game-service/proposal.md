# Proposal: Integrate Pekko ActorSystem in game-service

## Why

[`docs/pekko-game-actor-openspec-roadmap.md`](../../../docs/pekko-game-actor-openspec-roadmap.md) **阶段 1** 要求在 `game-service` 进程内嵌入 **Apache Pekko Typed** 的 **`ActorSystem`**，验证生命周期与消息路径，为后续 gRPC 桥接、会话 Actor、世界 Actor 打基础。基线能力 [`pekko-game-baseline`](../../../openspec/specs/pekko-game-baseline/spec.md) 已冻结 BOM 与 JDK；本变更在 **不承载业务玩法** 的前提下完成 **运行时嵌入**。

## What Changes

- 在 **`game-service`** 模块声明 **`pekko-actor-typed_2.13`**（版本由父 POM BOM 管理，不写版本号）。
- 通过 **Spring `@Configuration` + `@Bean`** 暴露 **进程内单例 `ActorSystem`**，并实现 **有序停机**（`terminate()` + 等待终止完成），与现有 [`GameServiceApplication`](../../../game-service/src/main/java/com/clawai/gatedemo/game/GameServiceApplication.java) 的 **关闭钩子 / `CountDownLatch`** 协调（见 `design.md`）。
- 在 `game-service/src/main/resources` 增加 **`application.conf`**（HOCON），承载 Pekko 配置入口（含系统名、默认 dispatcher 等占位）。
- **测试**：`pekko-actor-testkit-typed_2.13`（test scope，版本由 BOM）用于单元测试；可选 **`@SpringBootTest`** 验证 Bean 存在与可 spawn 简单 Typed Actor。
- **本变更不包含**：gRPC 入站桥接、消息信封规范、Player/City 等业务 Actor（由后续阶段变更完成）。

本变更 **无对外 API 契约**（gRPC proto 不变）；**非 BREAKING**，除非调用方错误地假设 `game-service` 无线程池新增（文档中说明资源侧影响）。

## Capabilities

### New Capabilities

- `game-pekko-actor-runtime`：`game-service` 内 **Pekko `ActorSystem` 单例**、**HOCON 配置**、**与 Spring 一致的生命周期与停机顺序**、**最小 Typed Actor 验证路径**（含测试要求）。

### Modified Capabilities

- （无）不修改既有规格中的业务行为；实现遵循已落地的 **`pekko-game-baseline`**。

## Impact

- **Maven**：`game-service/pom.xml` 增加 `pekko-actor-typed_2.13`；测试增加 `pekko-actor-testkit_2.13`（test）。
- **源码**：新增配置类、可选 `Behavior` / 包结构占位；**不改变**现有 gRPC 服务启动逻辑，仅增加 Actor 运行时。
- **运维**：进程内增加 Actor 线程池与邮箱；日志中可出现 Pekko 组件名（阶段 1 以 **INFO** 打系统名即可）。
- **依赖**：以 **父 POM 已 import 的 `pekko-bom_2.13`** 为准（参见 `pekko-game-baseline`）。

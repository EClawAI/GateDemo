# Proposal: Pekko Game baseline and BOM

## Why

在 game 服务引入 Apache Pekko Typed Actor 之前，需要先在仓库层面**冻结 JDK、Pekko 版本线与 BOM 管理方式**，否则后续 OpenSpec 变更会出现依赖版本漂移、与 gRPC/Netty 并存假设不一致等问题。本变更对应路线图 [`docs/pekko-game-actor-openspec-roadmap.md`](../../../../docs/pekko-game-actor-openspec-roadmap.md) **阶段 0**。

## What Changes

- 在父 POM 中声明 **Apache Pekko 1.x BOM**（`pekko-bom_2.13`），统一后续 `pekko-*` 构件版本来源。
- 将项目 **构建与运行目标 JDK** 与团队标准对齐为 **Java 21**（父 POM `java.version` / compiler 配置）。**BREAKING**：在仍使用 JDK 17 的环境上全量构建将不再被支持，除非单独维持分支或回滚该属性。
- 在设计与规格中固化：**首版仅规划单机 Typed Actor**；**不**在基线中引入 Pekko Cluster / Remote；**禁止**将 Pekko **2.0.x milestone** 用于生产依赖。
- 记录 **gRPC `grpc-netty-shaded` 与 Netty / Pekko 并存**时的风险边界（按进程区分 game-service 与 gate/player）。

本变更**不**包含：在 `game-service` 中创建 `ActorSystem`、接入业务消息或 gRPC 桥接（由后续阶段 1+ 变更完成）。

## Capabilities

### New Capabilities

- `pekko-game-baseline`：Game 侧引入 Pekko 前的 **JDK 版本、BOM 坐标、Pekko 1.x 版本线、可选模块引入顺序、与 gRPC/Netty 并存假设** 等基线要求。

### Modified Capabilities

- （无）本变更不修改既有能力规格中的行为要求；仅新增 `pekko-game-baseline` 能力。

## Impact

- **构建**：父 POM `pom.xml`（`java.version`、可选 `maven-compiler-plugin` 显式版本）、`dependencyManagement` 增加 Pekko BOM。
- **模块**：首版实现可**仅**触及父 POM；`game-service` 在实现阶段可不新增 Pekko 依赖直至 `integrate-pekko-actor-system-in-game-service`。
- **文档**：路线图阶段 0 与 [`docs/actor_study.md`](../../../../docs/actor_study.md) 可交叉引用本 change。
- **系统**：运行时行为不变，直至后续 change 实际引入 Pekko 依赖与代码。

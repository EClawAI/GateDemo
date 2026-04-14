# pekko-game-baseline (Delta)

## Purpose

定义 Game 侧引入 Apache Pekko 之前必须在仓库中满足的 **JDK、BOM、版本线与模块引入顺序** 基线，避免依赖漂移并与 Gate–Game gRPC 栈协调。

## ADDED Requirements

### Requirement: 父 POM 导入 Pekko 1.x BOM

项目 SHALL 在父 POM（`gate-demo-parent`）的 `dependencyManagement` 中通过 `import` 作用域声明 **`org.apache.pekko:pekko-bom_2.13`**，版本 **SHALL** 为 **1.5.0**（或经评审的同 **1.x** 补丁版本，且全仓库一致）。所有 Pekko 模块依赖 MUST 从该 BOM 解析版本，不得在子模块中单独声明冲突的 Pekko 版本号。

#### Scenario: 子模块依赖不写 Pekko 版本号

- **WHEN** 某模块声明 `org.apache.pekko:pekko-actor-typed_2.13`（或 BOM 管理的其他 `pekko-*_2.13` 构件）
- **THEN** Maven 解析的版本与父 POM 导入的 BOM 一致
- **AND** 该模块未为同一构件再写独立 `<version>`（除非经豁免文档说明）

### Requirement: 构建目标为 Java 21

项目 SHALL 以 **Java 21** 作为 **`java.version`** 及编译 **source/target** 的默认目标；所有模块 SHALL 在文档化的 JDK 21 环境下可完成与父 POM 一致的构建。

#### Scenario: 父 POM 声明 Java 21

- **WHEN** 查看根 `pom.xml` 的 `properties` 与 compiler 配置
- **THEN** `java.version`（或等效）为 **21**
- **AND** 与团队 CI/本地标准 JDK 一致

### Requirement: 禁止生产使用 Pekko 2.0 milestone

项目 MUST NOT 将 **Pekko 2.0.x milestone**（例如 **`2.0.0-M1`**）作为生产依赖或父 BOM 的版本线；生产依赖 SHALL 限制在 **Apache Pekko 1.x** 发布线，直至团队以单独变更明确迁移到 **2.x GA**。

#### Scenario: 依赖树无 2.0 milestone

- **WHEN** 在合并依赖基线的变更后执行 `mvn dependency:tree`（针对将引入 Pekko 的模块）
- **THEN** 输出中不出现将 `org.apache.pekko` **2.0.0-M** 系列作为编译/运行依赖的路径（测试或文档工具除外且须评审）

### Requirement: Pekko 可选模块引入顺序

团队 SHALL 按以下顺序规划 Pekko 模块依赖：**先** `pekko-actor-typed`（Typed Actor）；**再按需** `pekko-stream`；**Pekko Remote / Cluster / Cluster Sharding** SHALL 仅在单独 OpenSpec 变更与运维前提下引入。在基线变更中 SHALL NOT 将 Remote 或 Cluster 加入父 BOM 之外的强制依赖。

#### Scenario: 基线不强制 Cluster

- **WHEN** 审查本仓库父 POM 与 game-service 的直接/托管依赖
- **THEN** 未因本基线变更而引入 `pekko-remote`、`pekko-cluster` 等集群模块

### Requirement: game-service 与 gRPC 的 Netty 并存假设

`game-service` SHALL 继续通过 **`grpc-netty-shaded`** 使用 gRPC；在仅引入 **单机 Typed Actor** 的阶段，团队 SHALL 将 **gRPC 的 shaded Netty** 与 **未来可能出现的 `io.netty.*`** 视为可并存栈，并在升级 **gRPC 或 Pekko** 时核对 **`mvn dependency:tree`**，避免未声明的 `io.netty` 版本冲突。

#### Scenario: 升级依赖时检查 Netty 线

- **WHEN** 某变更升级 gRPC 或新增 Pekko 相关依赖
- **THEN** 该变更的评审材料中包含相关模块的 **dependency 树** 或等价说明
- **AND** 记录是否存在多条 **`io.netty`** 版本线及处理结论

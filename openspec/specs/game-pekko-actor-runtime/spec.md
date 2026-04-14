# game-pekko-actor-runtime Specification

## Purpose

规定 **game-service** 如何暴露并关闭 **Apache Pekko Typed `ActorSystem`**，以及如何验证 **最小 Typed Actor** 消息路径；与 **`pekko-game-baseline`** 一致，**不**规定业务玩法或 gRPC 桥接。

## Requirements

### Requirement: game-service 声明 pekko-actor-typed 依赖

`game-service` 模块 SHALL 在 `pom.xml` 中声明 **`org.apache.pekko:pekko-actor-typed_2.13`**，版本 **MUST** 由父 POM 导入的 **`pekko-bom_2.13`** 管理（不在子模块写 `<version>`）。

#### Scenario: Maven 解析 Typed 依赖

- **WHEN** 在仓库根执行 `mvn -pl game-service dependency:tree`（或等效）
- **THEN** 出现 `pekko-actor-typed_2.13` 且无与 BOM 冲突的重复 Pekko 版本号

### Requirement: 单一 ActorSystem Bean

`game-service` SHALL 在 Spring 容器中提供 **恰好一个**  **`ActorSystem`** Bean（Typed API），供同进程内后续组件注入使用；`ActorSystem` 的**逻辑名称** SHALL 在 HOCON（见下条）中定义（例如 **`game`**）。

#### Scenario: 应用上下文内可解析 ActorSystem

- **WHEN** Spring 容器刷新完成且 `game-service` 处于运行状态
- **THEN** 可从上下文获取类型为 `org.apache.pekko.actor.typed.ActorSystem` 的单例 Bean
- **AND** 多次获取指向同一实例

### Requirement: classpath 提供 application.conf（HOCON）

`game-service` SHALL 在 `src/main/resources` 提供 **`application.conf`**，并包含 **`pekko`** 根配置节（至少含与 `ActorSystem` 名称一致的 **`pekko.actor.provider`** / **`pekko.actor.name`** 或等效 Typed 系统配置，具体键名以实现时 Pekko 版本文档为准）；配置 SHALL 可被 Pekko 默认配置合并机制加载。

#### Scenario: 配置文件存在且可打包

- **WHEN** 构建 `game-service` 产物 JAR
- **THEN** JAR 内含 **`application.conf`**
- **AND** 运行时 `ActorSystem` 使用该配置（或通过代码显式加载同资源）

### Requirement: 有序关闭 ActorSystem

`game-service` SHALL 在应用停止路径上 **调用** `ActorSystem.terminate()`，并 **等待** 终止完成（`whenTerminated` 或等效 API），**除非**达到文档化超时策略；关闭逻辑 MUST NOT 长期阻塞 **gRPC IO 线程**（在 IO 回调中仅投递到 Actor / 其它线程）。

#### Scenario: 关闭时终止被调用

- **WHEN** Spring 上下文关闭或等价停机路径触发 `game-service` 的销毁逻辑
- **THEN** `ActorSystem.terminate()` 被调用
- **AND** 实现代码在合理超时内等待系统终止完成或记录失败

### Requirement: 最小 Typed Actor 验证

项目 SHALL 提供 **自动化测试**（单元或集成），证明可 **spawn** 至少两个 **Typed** Actor（父子关系），并通过 **`tell` 或 `ask`** 完成一次**往返消息**；消息载荷可为占位类型（如 **`String`**），**无需**业务语义。

#### Scenario: 父子 tell 往返

- **WHEN** 执行上述测试
- **THEN** 子 Actor 收到消息并向父或可观测端点确认处理完成
- **AND** 测试稳定通过（无非确定睡眠依赖，或睡眠有清晰上界）

### Requirement: 测试依赖 pekko-actor-testkit-typed

`game-service` SHALL 在 **test** 作用域声明 **`org.apache.pekko:pekko-actor-testkit-typed_2.13`**（Typed `ActorTestKit`），版本由 **BOM** 管理。

#### Scenario: Testkit 可用于单元测试

- **WHEN** 运行 `game-service` 的测试编译与执行
- **THEN** 测试代码可使用 `ActorTestKit`（或当前 Pekko 版本等价测试 API）

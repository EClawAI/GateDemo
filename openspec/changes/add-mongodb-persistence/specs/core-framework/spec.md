## ADDED Requirements

### Requirement: core 模块作为底层核心业务框架

项目 SHALL 建立 `core` Maven 模块，定位为各上层微服务通用核心业务逻辑的承载层。core 与 common 的分工 SHALL 为：
- `common`：数据定义层（Proto 生成类、共享 DTO），轻依赖，所有模块可引用
- `core`：核心业务框架层（持久化、缓存、事件等通用业务抽象），可引入 MongoDB/Spring 等较重依赖，上层 service 按需引用

依赖关系 SHALL 为：`common ← core ← game-service / gate-service / ...`。core MUST 依赖 common。

#### Scenario: 模块依赖链正确
- **WHEN** game-service 引入 core 依赖
- **THEN** 可传递获得 common 中的 Proto/DTO 类
- **AND** 可使用 core 中的持久化抽象等核心业务框架能力

### Requirement: core 包结构按职责域划分

core 模块的 Java 包 SHALL 以 `com.clawai.gatedemo.core` 为根，按职责域划分子包。本次 MUST 创建 `persistence` 子包并实现 MongoDB 持久化抽象。其余子包（`model`、`event`、`cache`、`config` 等）为预留扩展，本次不要求实现。

#### Scenario: 包结构清晰
- **WHEN** 开发者查看 core 模块源码
- **THEN** 可通过包名直接识别各职责域（如 `persistence` = 持久化）

### Requirement: core 模块 Maven 配置

core 的 `pom.xml` SHALL 以 `gate-demo-parent` 为 parent，artifactId 为 `core`。SHALL 引入 `spring-boot-starter-data-mongodb` 和 `common` 模块依赖。parent `pom.xml` MUST 在 `<modules>` 中注册 core，MUST 在 `<dependencyManagement>` 中声明 core artifact。

#### Scenario: Maven 构建包含 core
- **WHEN** 在项目根目录执行 `mvn compile`
- **THEN** core 模块被正确编译
- **AND** core 的依赖（common、spring-data-mongodb）正确解析

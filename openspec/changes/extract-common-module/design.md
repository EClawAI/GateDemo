# 抽取公共模块 (extract-common-module)

## Context

- **当前状态**：Proto 生成代码在 gate-service 和 game-service 中各自通过 protobuf-maven-plugin 编译，重复维护且版本可能不一致；ApiResponse 在 login-service 和 center-service 中重复定义，结构相同；无共享 DTO、协议模型或工具类模块，公共能力无法复用。
- **问题**：Proto 变更需在多处同步编译；ApiResponse 等 DTO 重复定义，违反 DRY；新增公共能力时无处安放。
- **约束**：保持现有 Maven 多模块结构；不改变对外 API 契约；common 模块应为最小依赖（仅包含 protobuf、lombok 等必要依赖），避免传递过多依赖到各服务。

## Goals / Non-Goals

**Goals:**

- 新建 common Maven 子模块，作为公共能力聚合点。
- 将 Proto 生成类统一在 common 模块编译，gate-service、game-service 依赖 common 获取生成类，移除各自模块内的 proto 编译配置。
- 将 ApiResponse 等重复 DTO 从 login-service、center-service 抽取到 common，移除重复定义。
- 将可复用的工具类（如 TraceIdGenerator、JSON 工具等）迁移至 common，供各模块引用。

**Non-Goals:**

- 不在此 change 中抽取 gate-service 的全部 protocol 包（如 WrappedMessage、MessageHeader 等），仅 Proto 生成类；protocol 模型若被 gate、player-client 等多处引用，可后续迭代。
- 不改变 proto 文件位置（可保留在项目根或 proto/ 目录）；仅改变编译产出位置。
- 不引入新框架或依赖管理工具。

## Decisions

1. **common 模块结构与包名**
   - 新建 `common/` 目录，`artifactId` 为 `gate-demo-common`；包名 `com.clawai.gatedemo.common`；子包可包括 `dto`、`proto`（生成类输出）、`util` 等。
   - **理由**：与现有 `com.clawai.gatedemo.*` 命名一致；清晰区分职责。

2. **Proto 编译迁移至 common**
   - 在 common 的 pom.xml 中配置 protobuf-maven-plugin，protoSourceRoot 指向项目根或 `../proto`；输出到 `common/target/generated-sources/protobuf`。
   - gate-service、game-service 移除各自的 protobuf 编译配置，添加对 common 的依赖；通过 `common` 获取生成的 `*Grpc`、`*Proto` 等类。
   - **理由**：单一编译源，避免重复；proto 变更只需在 common 一次编译。

3. **ApiResponse 抽取**
   - 在 common 的 `com.clawai.gatedemo.common.dto` 包下定义 `ApiResponse<T>`，保留 `success(T)`、`error(int, String)` 方法及 code、message、data 字段。
   - login-service、center-service 删除各自的 `model.ApiResponse`，改为 `import com.clawai.gatedemo.common.dto.ApiResponse`。
   - **理由**：两处实现相同，统一可减少维护成本；若有细微差异，需在迁移时统一约定。

4. **工具类聚合**
   - 将可复用的工具类（如 TraceIdGenerator、字符串/日期工具等）迁移至 common 的 `util` 包；原模块改为依赖 common 并调用。
   - **理由**：避免多模块重复实现；工具类通常无业务依赖，适合放 common。

5. **父 pom 与模块声明**
   - 在父 pom 的 `<modules>` 中增加 `<module>common</module>`；在 dependencyManagement 中增加 common 依赖声明；各子模块按需依赖 common。
   - **理由**：标准 Maven 多模块管理；构建顺序上 common 需先于 gate-service、game-service 等。

## Risks / Trade-offs

- **[风险]** common 依赖变更影响所有消费者 → mitigation：common 保持稳定，仅放真正共享的代码；新增依赖时谨慎评估。
- **[权衡]** Proto 生成类放 common 后，gate、game 均依赖 common → 若 gate 不需要 game 的某些 proto，会有多余依赖；可接受，因 gRPC 场景下 gate 与 game 通常共享同一 proto 定义。
- **[风险]** ApiResponse 迁移时 login/center 的序列化字段名可能变化 → 保持字段名一致（code、message、data），避免 API 破坏性变更。

## Migration Plan

- **实现顺序**：1) 创建 common 模块与 pom；2) 迁移 Proto 编译到 common，调整 gate/game 依赖；3) 抽取 ApiResponse 到 common，更新 login/center import；4) 迁移工具类到 common（可选，按需）；5) 验证编译、测试通过。
- **部署**：无运行时数据迁移；各服务需重新打包，依赖 common 的新版本。
- **回滚**：恢复各模块的 proto 编译配置与 ApiResponse 定义；从父 pom 移除 common 模块；各模块移除对 common 的依赖。

## Open Questions

- TraceIdGenerator 当前在 gate-service，若 login/center 也需使用，是否迁移到 common？建议：若有多处使用则迁移；否则可暂留 gate-service。
- common 是否需要单独发布到 Maven 仓库？若仅单体应用内部使用，无需发布，通过模块依赖即可。

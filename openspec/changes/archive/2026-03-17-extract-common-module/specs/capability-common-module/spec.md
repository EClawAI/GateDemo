# capability-common-module (Delta)

## Purpose

通过新建 common Maven 模块统一 Proto 生成类、共享 DTO 与工具类，消除重复维护与编译，提升代码复用性。

## ADDED Requirements

### Requirement: common Maven 模块存在

系统 SHALL 提供名为 `gate-demo-common` 的 Maven 模块；SHALL 在父 pom 的 modules 中声明该模块；各需复用的子模块 MUST 通过 Maven 依赖引用 common。

#### Scenario: 模块声明与依赖
- **WHEN** 执行父项目 `mvn install` 或 `mvn package`
- **THEN** common 模块被优先编译
- **AND** gate-service、game-service、login-service、center-service 可依赖 common

#### Scenario: common 包结构
- **WHEN** 查看 common 模块源码
- **THEN** 存在 `com.clawai.gatedemo.common` 包及子包（如 dto、util）
- **AND** 公共能力集中于此

### Requirement: Proto 生成类统一在 common 编译

系统 SHALL 在 common 模块中配置 protobuf-maven-plugin，将 proto 文件编译为 Java 类；gate-service 与 game-service SHALL 移除各自的 proto 编译配置，改为依赖 common 获取生成类；MUST 不再在 gate-service、game-service 内重复编译 proto。

#### Scenario: common 编译 proto
- **WHEN** 执行 `mvn compile` 于 common 或父项目
- **THEN** proto 文件在 common 中编译
- **AND** 生成类输出到 common 的 target/generated-sources

#### Scenario: gate-service 使用 common 的 proto 类
- **WHEN** gate-service 需要调用 Game gRPC 或使用 proto 定义的消息
- **THEN** 通过依赖 common 获取 *Grpc、*Proto 等类
- **AND** 无需在 gate-service 中配置 protobuf 插件

#### Scenario: game-service 使用 common 的 proto 类
- **WHEN** game-service 需要实现 gRPC 服务或处理 proto 消息
- **THEN** 通过依赖 common 获取生成类
- **AND** 无需在 game-service 中配置 protobuf 插件

### Requirement: ApiResponse 等共享 DTO 位于 common

系统 SHALL 在 common 中定义 ApiResponse\<T\> 等通用响应结构；login-service 与 center-service SHALL 移除各自的 ApiResponse 定义，改为引用 common 中的实现；MUST 保持 ApiResponse 的字段（code、message、data）与序列化行为不变。

#### Scenario: ApiResponse 定义在 common
- **WHEN** 查看 common 模块
- **THEN** 存在 ApiResponse 类，包含 success、error 方法及 code、message、data
- **AND** 可被 login-service、center-service 引用

#### Scenario: login-service 使用 common ApiResponse
- **WHEN** login-service 的 HTTP 接口返回统一响应
- **THEN** 使用 common 的 ApiResponse
- **AND** 响应 JSON 格式与迁移前一致

#### Scenario: center-service 使用 common ApiResponse
- **WHEN** center-service 的 HTTP 接口返回统一响应
- **THEN** 使用 common 的 ApiResponse
- **AND** 响应 JSON 格式与迁移前一致

### Requirement: 工具类可聚合于 common

系统 MAY 将可复用的工具类（如 TraceIdGenerator、字符串/日期工具）迁移至 common 的 util 包；SHALL 确保迁移后各引用方通过依赖 common 获取工具类；MUST 不破坏现有调用方的行为。

#### Scenario: 工具类迁移
- **WHEN** 某工具类被多个模块使用
- **THEN** 可迁移至 common.util 包
- **AND** 原模块改为 import common 中的类

### Requirement: common 依赖最小化

系统 SHALL 使 common 模块仅依赖必要的库（如 protobuf、lombok、slf4j 等）；SHALL 避免引入重量级框架依赖；MUST 不将业务逻辑放入 common。

#### Scenario: common 依赖检查
- **WHEN** 查看 common 的 pom.xml
- **THEN** 依赖列表精简
- **AND** 无非必要传递依赖（除非 proto/grpc 必需）

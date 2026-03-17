# capability-api-versioning Specification

## Purpose
TBD - created by archiving change add-api-versioning-and-proto-docs. Update Purpose after archive.
## Requirements
### Requirement: API 版本策略与废弃流程文档化

系统 SHALL 在文档中明确 API 版本策略（URL 版本 /api/v1、/api/v2 或 Header 版本）；SHALL 文档化废弃流程：deprecated 标记 → 过渡期 → removed；MUST 说明何时创建新版本、向后兼容原则；SHALL 适用于 login-service、center-service 的 REST API 及 proto 定义的服务。

#### Scenario: 版本策略可查阅
- **WHEN** 开发者查阅版本策略文档
- **THEN** 可明确了解 URL 或 Header 的版本约定
- **AND** 知晓新版本发布与旧版本废弃的流程

#### Scenario: 废弃过渡期
- **WHEN** 某 API 或 proto 被标记为 deprecated
- **THEN** 文档说明过渡期时长（如 6 个月）及替代方案
- **AND** 过渡期内调用方仍可使用，过渡期结束后移除

### Requirement: Proto 文件具备完整注释并生成文档

系统 SHALL 为 game_service.proto 的每个 message、field、service、rpc 添加详细注释；SHALL 使用 protoc-gen-doc 或 buf doc 生成 HTML/Markdown 文档；MUST 输出到 docs/proto/ 或约定目录；SHALL 在 proto 变更时重新生成，保持同步。

#### Scenario: message/field 注释完整
- **WHEN** 查阅生成的 Proto 文档
- **THEN** 每个 message、field 有语义说明
- **AND** 调用方可理解各字段含义、取值范围、使用场景

#### Scenario: service/rpc 注释完整
- **WHEN** 查阅生成的 Proto 文档
- **THEN** 每个 service、rpc 有用途、请求响应、错误码说明
- **AND** 支持中文注释

### Requirement: buf breaking change 检测

系统 SHALL 在项目中使用 buf 配置（buf.yaml、buf.gen.yaml）；SHALL 配置 breaking 规则（如 FILE、PACKAGE、FIELD 等）；MUST 在 CI 中执行 buf breaking 检测；SHALL 禁止非兼容变更合入主分支；MUST 文档说明如何修复 breaking 变更及例外流程。

#### Scenario: CI 检测通过
- **WHEN** 变更仅包含非破坏性修改（如新增 field、新增 rpc）
- **THEN** buf breaking 检测通过
- **AND** CI 可继续执行后续步骤

#### Scenario: CI 检测失败
- **WHEN** 变更包含破坏性修改（如删除 field、修改类型、修改 rpc 签名）
- **THEN** buf breaking 检测失败
- **AND** CI 失败，需修改或走例外流程（如显式升级主版本）

### Requirement: 接口变更 CHANGELOG

系统 SHALL 维护接口变更 CHANGELOG（docs/CHANGELOG-api.md 或等效）；SHALL 在每次 proto 或 REST 接口变更时更新；MUST 标注 breaking/non-breaking、影响范围、迁移建议；SHALL 按版本或日期组织；MUST 便于调用方查阅升级影响。

#### Scenario: 变更可追溯
- **WHEN** 调用方查阅 CHANGELOG
- **THEN** 可看到历史变更及 breaking 标注
- **AND** 了解如何迁移到新版本

#### Scenario: breaking 变更有迁移指引
- **WHEN** 某次变更为 breaking
- **THEN** CHANGELOG 中说明影响范围与迁移步骤
- **AND** 必要时提供示例或兼容层说明

### Requirement: 文档与 CI 集成

系统 SHALL 将 proto 文档生成纳入 Maven/Gradle 或 CI 流程；SHALL 在 proto 变更时自动重新生成文档；MUST 确保 buf breaking 检测在 PR 或 push 时执行；SHALL 文档说明如何本地执行 buf 与 protoc-gen-doc。

#### Scenario: 本地生成文档
- **WHEN** 开发者在本地执行文档生成命令
- **THEN** 可在约定目录得到最新 Proto 文档
- **AND** 与 CI 生成的输出一致


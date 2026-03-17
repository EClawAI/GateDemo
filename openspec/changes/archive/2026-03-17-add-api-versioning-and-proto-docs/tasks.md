# add-api-versioning-and-proto-docs 任务清单

## 1. API 版本策略文档

- [x] 1.1 创建 docs/api-versioning-policy.md
- [x] 1.2 文档化版本策略：URL 版本（/api/v1）或 Header 版本，选定项目标准
- [x] 1.3 文档化废弃流程：deprecated 标记、过渡期、removed 时机
- [x] 1.4 说明向后兼容原则与何时创建新版本
- [x] 1.5 与 login-service、center-service 现有 /api/v1 路由对应说明

## 2. Proto 注释与文档生成

- [x] 2.1 为 game_service.proto 的每个 message 添加注释（用途、字段说明）
- [x] 2.2 为每个 field 添加注释（含义、取值范围、示例）
- [x] 2.3 为每个 service、rpc 添加注释（用途、请求响应、错误码）
- [x] 2.4 引入 protoc-gen-doc 或 buf doc 插件
- [x] 2.5 配置 Maven/Gradle 或脚本，生成 HTML/Markdown 至 docs/proto/
- [x] 2.6 文档说明如何本地执行文档生成

## 3. buf breaking change 检测

- [x] 3.1 在项目根或 proto/ 目录创建 buf.yaml
- [x] 3.2 配置 buf breaking 规则（如 BUF_CHECK_BREAKING_CONFIG）
- [x] 3.3 创建 buf.gen.yaml（若需代码生成）
- [x] 3.4 在 CI（GitHub Actions / Jenkins / 其他）中添加 buf breaking 检测步骤
- [x] 3.5 文档说明 buf 用法、规则含义、如何修复 breaking 及例外流程
- [x] 3.6 验证：故意引入 breaking 变更，确认 CI 失败

## 4. 接口变更 CHANGELOG

- [x] 4.1 创建 docs/CHANGELOG-api.md 或扩展现有 CHANGELOG
- [x] 4.2 定义 CHANGELOG 格式：日期/版本、变更类型（breaking/non-breaking）、影响范围、迁移建议
- [x] 4.3 填写首次条目（当前 proto 与 REST 接口的基线）
- [x] 4.4 将 CHANGELOG 更新纳入 PR checklist 或 Code Review 要求
- [x] 4.5 文档说明 CHANGELOG 维护规范

## 5. CI 集成与文档同步

- [x] 5.1 在 CI 中加入 proto 文档生成步骤（可选，或由开发者本地生成后提交）
- [x] 5.2 确保 buf breaking 在每次 proto 变更时执行
- [x] 5.3 编写 README 或 docs 说明：版本策略、Proto 文档位置、buf 用法、CHANGELOG 规范
- [x] 5.4 若有其他 proto 文件，统一纳入注释与文档生成范围

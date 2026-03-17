## Why

当前存在以下缺口：
- login/center 使用 /api/v1 路径但无版本迁移策略，新版本发布方式不明确
- game_service.proto 无详细注释或文档，调用方难以理解接口语义
- 无接口变更记录或兼容性检查，存在 breaking change 风险

## What Changes

建立 API 版本管理与 Proto 文档体系：
- 文档化 API 版本策略和废弃流程
- 为 proto 文件添加详细注释，使用 protoc-gen-doc 生成文档
- 使用 buf 进行 breaking change 检测，维护接口变更 CHANGELOG

## 核心功能

1. **API 版本管理策略**
   - 版本号使用规范（v1、v2）
   - 废弃流程与过渡期
   - 向后兼容原则文档化

2. **Proto 文档化（protoc-gen-doc）**
   - proto 文件添加字段、RPC 注释
   - 自动生成 HTML/Markdown 文档
   - 集成到 CI 或 docs 目录

3. **buf breaking change 检测**
   - buf breaking 规则配置
   - CI 中接入 breaking 检查
   - 防止非兼容变更合入

4. **接口变更 CHANGELOG**
   - 记录 proto/REST 接口变更
   - 标注 breaking/non-breaking

## Impact

- 影响 login-service（版本策略文档、CHANGELOG）
- 影响 center-service（版本策略文档、CHANGELOG）
- 影响 proto/ 目录（注释、buf 配置、文档生成）

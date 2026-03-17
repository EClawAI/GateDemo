# API 版本管理与 Proto 文档 (design.md)

## Context

- **当前状态**：login/center 使用 /api/v1 路径但无版本迁移策略，新版本发布方式不明确。game_service.proto 仅有简要注释，缺乏 message、field、service、rpc 的完整文档，调用方难以理解接口语义。无接口变更记录或兼容性检查，存在 breaking change 风险。
- **问题**：版本策略缺失导致升级混乱；Proto 文档不足影响对接效率；缺乏变更检测易引入不兼容变更。
- **约束**：不使用 Spring 特有版本注解；proto 文档化与 buf 检测为独立工具链，不改变既有 proto 语法；REST API 版本策略为文档与路由约定。

## Goals / Non-Goals

**Goals:**
- 文档化 API 版本策略（URL 版本 vs Header 版本）与废弃流程（deprecated → removed）
- 为 game_service.proto 添加详细注释（每个 message、field、service、rpc），使用 protoc-gen-doc 生成 HTML/Markdown 文档
- 使用 buf 进行 proto breaking change 检测，在 CI 中接入
- 维护接口变更 CHANGELOG，标注 breaking/non-breaking

**Non-Goals:**
- 不改变现有 proto 或 REST 接口的运行时行为
- 不引入 Spring Boot/Spring Cloud 版本能力
- 不实现自动化的客户端代码生成或 SDK 发布

## Decisions

1. **API 版本策略**
   - 文档化两种可选策略：URL 版本（/api/v1、/api/v2）与 Header 版本（Accept: application/vnd.api+v1）；选定一种作为项目标准；文档说明何时创建新版本、废弃流程、过渡期。
   - **理由**：明确规范，减少争议；login/center 已用 /api/v1，可延续 URL 版本。

2. **废弃流程**
   - 新版本发布时，旧版本标记 deprecated，文档说明过渡期（如 6 个月）；过渡期内同时支持新旧版本；过渡期结束后移除旧版本，更新 CHANGELOG。
   - **理由**：给调用方迁移时间，避免强行断服。

3. **Proto 文档化**
   - 在 game_service.proto 中为每个 message、field、service、rpc 添加完整注释（支持中文）；使用 protoc-gen-doc（或 buf 的 doc 插件）生成 HTML/Markdown；输出到 docs/proto/ 或 build/docs/proto/；可集成到 Maven/Gradle 或 CI。
   - **理由**：提升可读性，便于对接方理解与对接。

4. **buf breaking change 检测**
   - 在项目根目录或 proto/ 下添加 buf.yaml、buf.gen.yaml；配置 buf breaking 规则（如 FILE、PACKAGE、FIELD 等）；CI 中执行 buf breaking 检测，禁止非兼容变更合入。
   - **理由**：防止意外破坏兼容性，保障多版本共存。

5. **接口变更 CHANGELOG**
   - 在 docs/CHANGELOG-api.md 或 CHANGELOG.md 中维护接口变更记录；每次 proto 或 REST 变更时更新；标注 breaking/non-breaking、影响范围、迁移建议。
   - **理由**：可审计、可追溯，方便调用方升级。

## Risks / Trade-offs

- **[权衡]** buf 规则过严可能阻碍合理演进 → 根据团队实践调整规则，允许 FIELD_ADD 等非破坏性变更；文档说明哪些变更需走例外流程。
- **[风险]** 文档与代码不同步 → 将 proto 文档生成与 CHANGELOG 更新纳入 PR checklist 或 CI 检查；重大变更需 review。
- **[风险]** 多版本并存增加维护成本 → 控制活跃版本数量（如最多 2 个），及时废弃旧版本。

## Migration Plan

- **实现顺序**：先编写 API 版本策略文档 → 为 proto 添加注释 → 配置 protoc-gen-doc 生成文档 → 配置 buf 与 breaking 检测 → 接入 CI → 建立 CHANGELOG 模板与首次条目。
- **部署**：文档与 buf 检测为开发流程改进，无运行时影响；CI 配置更新后生效。
- **回滚**：移除 CI 中的 buf 检测步骤即可；文档可保留。

## Open Questions

- protoc-gen-doc 与 buf 的 doc 生成哪个更符合团队习惯？buf 生态更统一，建议优先 buf。
- REST API 是否需在代码中显式支持多版本路由？若当前仅 v1，文档化策略即可；若未来有 v2，需提前设计路由结构。

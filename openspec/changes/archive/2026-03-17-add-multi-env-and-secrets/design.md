# 多环境与敏感信息管理 (design.md)

## Context

- **当前状态**：各服务仅一份配置文件（如 application.yml），通过环境变量覆盖部分项；无法区分 dev/prod 的日志级别、Redis 单机/集群、TLS 开关等；Redis 密码等敏感信息在配置中明文存在。
- **问题**：开发与生产共用一个配置，易误用生产资源或泄露配置；敏感信息硬编码存在安全风险；dev/prod 差异化需求（日志、Redis、TLS）无法优雅支持。
- **约束**：不使用 Spring Boot Profile 或 Spring Cloud Config；通过启动参数或环境变量切换；保持与现有配置加载逻辑的兼容。

## Goals / Non-Goals

**Goals:**
- 为每个服务创建 config-dev.yml、config-prod.yml 多环境配置文件
- 通过启动参数或环境变量 APP_ENV=dev|prod 切换
- 敏感信息通过 ${ENV_VAR} 占位符外置，避免硬编码
- dev/prod 差异化：日志级别、Redis 单机/集群、TLS 开关

**Non-Goals:**
- 不实现动态配置刷新或配置中心
- 不引入 Spring Boot 或 Spring Cloud 配置能力
- 不在此 change 中实现完整的密钥管理服务（如 Vault）

## Decisions

1. **多环境配置文件命名**
   - 每个服务（gate、game、login、center）拥有 config-dev.yml、config-prod.yml；主配置（如 application.yml）可保留为公共基础，或由 APP_ENV 决定加载哪个文件。
   - **理由**：显式分离 dev/prod，减少误配；文件名即环境，便于理解。

2. **环境切换方式**
   - 通过启动参数 `-DAPP_ENV=dev` 或环境变量 `APP_ENV=dev` 指定；加载逻辑在应用启动时读取该值，决定加载 config-dev.yml 或 config-prod.yml。
   - **理由**：与 Spring 解耦；部署脚本或容器编排可统一设置 APP_ENV。

3. **敏感信息外置**
   - 在配置文件中使用 `${REDIS_PASSWORD}`、`${DB_PASSWORD}` 等占位符；在加载配置时，从 System.getenv() 或 System.getProperty() 解析并替换；未设置时可根据策略报错或使用默认值（仅 dev）。
   - **理由**：密码不落盘，符合安全实践；与环境变量配合，便于 K8s Secret、Docker secret 等注入。

4. **dev/prod 差异化项**
   - 日志级别：dev 使用 debug，prod 使用 info
   - Redis：dev 可用单机，prod 使用 Sentinel 或集群
   - TLS：dev 关闭，prod 开启
   - **理由**：开发环境便于调试，生产环境注重安全与性能。

5. **配置加载顺序**
   - 先加载公共配置（如有），再按 APP_ENV 加载 config-{env}.yml，后者覆盖前者；环境变量替换在最终合并后的配置上执行。
   - **理由**：公共项只写一次，环境差异集中管理。

## Risks / Trade-offs

- **[风险]** 环境变量未设置导致启动失败 → 在启动时检查必需的环境变量，缺失时明确报错；在文档中列出各服务所需的 ENV 清单。
- **[风险]** 占位符与 YAML 语法冲突 → 使用明确的占位符格式（如 ${VAR}），在解析时单独处理，避免与 YAML 保留字符冲突。
- **[权衡]** 配置文件数量增加 → 通过清晰的命名与目录结构管理；可考虑 config/common.yml 抽取公共部分。

## Migration Plan

- **实现顺序**：先实现配置加载逻辑（APP_ENV + 多文件）→ 抽取现有配置到 config-dev.yml / config-prod.yml → 将敏感项改为占位符并文档化所需环境变量。
- **部署**：部署脚本需设置 APP_ENV 及各类密码环境变量；docker-compose、K8s 需更新 env 配置。
- **回滚**：保留原有单文件配置的加载方式，通过配置开关或回退部署可恢复。

## Open Questions

- 是否支持 config-test.yml 等更多环境？可预留 APP_ENV 扩展点。
- 占位符默认值语法（如 ${VAR:default}）是否支持？建议在解析器中实现，便于 dev 本地不设 ENV 也能启动。

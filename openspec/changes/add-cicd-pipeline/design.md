## Context

- **当前状态**：项目无任何 CI/CD 配置，代码提交后无法自动构建与验证；Maven 未配置 JaCoCo 插件，测试覆盖率无法量化；PR 合入与主分支部署依赖人工执行构建、测试与镜像推送，缺乏自动化保障。
- **问题**：无法在合入前自动发现编译错误、测试失败；无法追踪测试覆盖率趋势；主分支部署需手动构建镜像，易遗漏或延迟。
- **约束**：使用 GitHub Actions 作为 CI/CD 平台；Maven 多模块构建；支持 Docker 镜像构建与推送；不依赖私有构建环境。

## Goals / Non-Goals

**Goals:**
- 建立 GitHub Actions workflow：PR/推送触发 build、test
- 添加 jacoco-maven-plugin，生成覆盖率报告（HTML/XML），支持覆盖率阈值
- 主分支通过后自动构建 Docker 镜像并推送到镜像仓库
- PR 必须通过构建与测试方可合并（通过 status check 体现）

**Non-Goals:**
- 不实现蓝绿部署、金丝雀等高级发布策略
- 不在此 change 中配置 K8s 部署或云环境
- 不强制覆盖率必须达到某阈值才能合并（可配置为 warning）

## Decisions

1. **双 workflow 模式**
   - PR / 任意分支推送：触发 `ci.yml`，执行 Maven 构建、测试、JaCoCo 报告
   - 主分支（main/master）推送：触发 `deploy.yml`，在 ci 通过后构建 Docker 镜像并推送
   - **理由**：PR 检查与部署职责分离；主分支部署可依赖 CI status，避免重复执行测试。

2. **JaCoCo 配置**
   - 在父 pom 或各模块 pom 中配置 jacoco-maven-plugin；执行 `mvn verify` 时生成报告到 target/site/jacoco
   - 支持 `jacoco.skip` 或 `-DskipTests` 时跳过 JaCoCo；支持 `minimum` 等阈值配置（可选，先为 warning）
   - **理由**：标准 Maven 集成方式；报告可上传为 job artifact 或发布到页面。

3. **Docker 构建策略**
   - 多服务分别构建：gate-service、game-service、login-service、center-service 等各建 Dockerfile；workflow 中逐个构建并推送
   - 镜像标签：主分支使用 commit SHA 短哈希 或 `latest`；可按需增加 `v*` 语义化标签
   - **理由**：与现有 docker-compose 多服务模型一致；便于按服务独立发布。

4. **镜像仓库**
   - 默认推送至 GitHub Container Registry (ghcr.io) 或 Docker Hub；通过仓库 Secrets（如 `DOCKER_USERNAME`、`DOCKER_PASSWORD`）配置
   - **理由**：公有仓库可免费使用；私有仓库需配置相应 Secret。

## Risks / Trade-offs

- **[风险]** Maven 构建时间长导致 PR 反馈慢 → 使用缓存（actions/cache）缓存 Maven 依赖；必要时拆分仅编译与全量测试。
- **[权衡]** 主分支每次推送都构建镜像 → 可改为仅 tag 触发或按路径过滤；初期保持简单，后续按需优化。
- **[风险]** 镜像推送权限泄露 → 使用 GitHub Secrets，不在日志中输出密码；限制 workflow 仅主分支可推送。

## Migration Plan

- **实现顺序**：先添加 jacoco-maven-plugin 到 pom → 创建 ci.yml（build+test）→ 验证 PR 检查 → 创建 deploy.yml → 配置 Secrets 并验证镜像推送。
- **部署**：workflow 文件合入即生效；需在仓库 Settings 中配置 Docker 推送所需的 Secrets。
- **回滚**：删除或禁用 workflow 文件；恢复 pom 中 JaCoCo 配置即可。

## Open Questions

- 是否将 JaCoCo 报告上传为 GitHub Actions artifact，供开发者下载？建议是。
- 主分支部署是否仅针对带 tag 的推送（如 v1.0.0）？可根据团队习惯选择。

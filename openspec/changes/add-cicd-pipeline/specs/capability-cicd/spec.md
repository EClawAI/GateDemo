# capability-cicd (Delta)

## Purpose
建立 GitHub Actions CI/CD 流水线，实现提交/PR 自动构建与测试、JaCoCo 覆盖率报告，以及主分支自动构建并推送 Docker 镜像。

## ADDED Requirements

### Requirement: PR 与推送触发构建与测试

系统 SHALL 配置 GitHub Actions workflow，在 PR 或分支推送时触发；SHALL 执行 Maven 多模块构建（mvn clean install 或 verify）；SHALL 执行单元测试；MUST 在测试失败时使 workflow 失败，并阻止 PR 合并（通过 status check）。

#### Scenario: PR 触发 CI
- **WHEN** 开发者提交 PR
- **THEN** CI workflow 自动运行 Maven 构建与测试
- **AND** 构建或测试失败时，PR 显示 failed check，不可合并（若仓库配置了 branch protection）

#### Scenario: 构建成功
- **WHEN** 所有模块编译通过且测试通过
- **THEN** workflow 标记为 success
- **AND** PR 可通过（若其他 check 也通过）

### Requirement: JaCoCo 生成覆盖率报告

系统 SHALL 在 Maven 构建中集成 jacoco-maven-plugin；SHALL 在 test 阶段收集覆盖率并生成报告；MUST 生成 HTML 报告（target/site/jacoco）及 XML 报告（供 CI 解析）；SHALL 支持通过配置设置覆盖率阈值（可选）。

#### Scenario: 执行 Maven 构建生成报告
- **WHEN** 执行 mvn verify 或等效命令
- **THEN** JaCoCo 在测试后生成覆盖率报告
- **AND** HTML 报告可本地查看，XML 可被 CI 工具解析

#### Scenario: CI 中保留覆盖率报告
- **WHEN** CI workflow 完成测试
- **THEN** 覆盖率报告可作为 artifact 上传或发布
- **AND** 开发者可下载查看覆盖率详情

### Requirement: 主分支自动构建并推送 Docker 镜像

系统 SHALL 配置主分支（main 或 master）推送触发的 workflow；SHALL 在 CI 通过（或内联执行）后构建各服务 Docker 镜像；SHALL 将镜像推送到配置的镜像仓库（如 ghcr.io）；MUST 使用 Secrets 存储推送凭证，不暴露于日志。

#### Scenario: 主分支推送触发部署
- **WHEN** 代码推送到 main 分支
- **THEN** deploy workflow 执行 Docker 构建与推送
- **AND** 各服务镜像成功推送到镜像仓库

#### Scenario: 构建失败不推送
- **WHEN** Maven 构建或测试失败
- **THEN** 不执行 Docker 构建与推送
- **AND** workflow 整体标记为失败

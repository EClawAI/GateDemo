## Why

当前存在以下缺口：
- 无任何 CI/CD 配置，代码提交后无法自动构建与验证
- Maven 未配置 JaCoCo 插件，无法量化测试覆盖率
- PR 合入与主分支部署缺乏自动化保障

## What Changes

建立 CI/CD 流水线与测试覆盖率机制：
- 创建 GitHub Actions workflow：build、test、docker push
- 添加 jacoco-maven-plugin，生成覆盖率报告
- 支持 PR 自动检查及主分支触发部署

## 核心功能

1. **GitHub Actions CI/CD**
   - 提交/PR 触发构建与测试
   - 多模块 Maven 构建
   - Docker 镜像构建与推送（主分支）

2. **JaCoCo 覆盖率**
   - 集成 jacoco-maven-plugin
   - 生成 HTML/XML 覆盖率报告
   - 支持覆盖率阈值配置

3. **PR 检查与主分支部署**
   - PR 必须通过测试方可合并
   - 主分支通过后自动构建并推送镜像

## Impact

- 影响 `.github/workflows/` 新增 workflow 文件
- 影响全部服务 `pom.xml` 添加 JaCoCo 配置

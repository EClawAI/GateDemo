# add-cicd-pipeline 任务清单

## 1. JaCoCo 插件配置

- [x] 1.1 在父 pom.xml 或各模块 pom 中添加 jacoco-maven-plugin 依赖与配置
- [x] 1.2 配置 report 阶段生成 HTML 报告到 target/site/jacoco，XML 报告到 target/site/jacoco/jacoco.xml
- [x] 1.3 可选：配置 minimum 等覆盖率阈值（先设为 warning 或跳过）
- [x] 1.4 确保 mvn verify 或 mvn test 能正确触发 JaCoCo 执行

## 2. CI workflow（PR / 构建 / 测试）

- [x] 2.1 创建 .github/workflows/ci.yml，配置 on: pull_request, push
- [x] 2.2 使用 actions/checkout 与 Java setup（如 actions/setup-java）
- [x] 2.3 使用 actions/cache 缓存 Maven 依赖（~/.m2）
- [x] 2.4 执行 mvn clean verify 或等效命令
- [x] 2.5 上传 JaCoCo 报告为 artifact（target/site/jacoco 或 jacoco.xml）
- [x] 2.6 验证 PR 提交后 CI 能正确运行并显示 status

## 3. Deploy workflow（主分支 Docker 推送）

- [x] 3.1 创建 .github/workflows/deploy.yml，配置 on: push branches: [main] 或 [master]
- [x] 3.2 复用 CI 步骤（或调用）确保构建与测试通过后再进行 Docker 构建
- [x] 3.3 配置 Docker buildx 或 docker build，为 gate、game、login、center 等服务分别构建镜像
- [x] 3.4 使用 docker login 与 push，凭证从 Secrets（如 GHCR 的 GITHUB_TOKEN 或 DOCKER_HUB 的 DOCKER_USERNAME/DOCKER_PASSWORD）读取
- [x] 3.5 镜像打标签：使用 commit SHA 短哈希或 latest，按需支持语义化版本

## 4. 文档与配置

- [x] 4.1 在 README 或 docs 中说明 CI/CD 流程：PR 检查、主分支部署
- [x] 4.2 文档化所需 GitHub Secrets（DOCKER_USERNAME、DOCKER_PASSWORD 或 GITHUB_TOKEN 等）
- [x] 4.3 确认各服务 Dockerfile 存在且可被 workflow 正确引用

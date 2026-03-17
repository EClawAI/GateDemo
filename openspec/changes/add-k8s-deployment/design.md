# Kubernetes 部署 (design.md)

## Context

- **当前状态**：Gate、Game、Login、Center、Redis 等服务仅有 docker-compose 本地编排，无 Kubernetes  manifests。部署依赖手工或脚本，无法在 K8s 集群上标准编排。配置与镜像耦合在本地，缺乏多环境（dev/staging/prod）差异化管理。
- **问题**：无法在 K8s 集群上部署；无弹性伸缩能力应对流量波动；配置与部署环境耦合，不利于多环境发布与回滚。
- **约束**：不使用 Spring Boot 或 Spring Cloud；K8s 清单需与现有服务端口、健康检查、资源占用保持一致；需支持 gate-service、game-service、login-service、center-service、Redis 的完整编排。

## Goals / Non-Goals

**Goals:**
- 为 gate-service、game-service、login-service、center-service、Redis 创建完整的 Kubernetes Deployment、Service、ConfigMap 清单
- 配置 HPA（HorizontalPodAutoscaler）支持基于 CPU/内存的弹性伸缩
- 资源 requests/limits 合理配置，避免 OOM 与资源抢占
- 可选 Helm Chart 统一管理多环境（dev/staging/prod）差异化部署
- 支持配置注入（ConfigMap/环境变量）实现外部化配置

**Non-Goals:**
- 不引入 Service Mesh（如 Istio）或 Ingress Controller 的高级路由
- 不实现 CI/CD 流水线（由 add-cicd-pipeline 负责）
- 不改变各服务既有协议或端口

## Decisions

1. **清单组织方式**
   - 在 `deploy/k8s/` 或 `k8s/` 目录下按服务分子目录（gate-service、game-service、login-service、center-service、redis）；每个服务包含 deployment.yaml、service.yaml、configmap.yaml；HPA 单独或与 Deployment 同目录。
   - **理由**：清晰分层，便于按服务独立更新与审计。

2. **ConfigMap 与配置注入**
   - 将 application.yml 中非敏感关键项（如 Redis host、gRPC 端口、心跳间隔等）提取至 ConfigMap；通过 volumeMount 或 envFrom 注入；敏感信息通过 Secret 或外部密钥管理。
   - **理由**：配置与镜像解耦，支持同一镜像多环境部署。

3. **HPA 策略**
   - 基于 CPU utilization 与 memory utilization 的 target；为 gate-service、game-service 等有状态或连接密集型服务配置 minReplicas/maxReplicas；Redis 单副本或 StatefulSet 不做 HPA。
   - **理由**：满足流量波动时的自动扩容，同时避免无状态服务过度伸缩。

4. **Helm Chart（可选）**
   - 在 `deploy/helm/` 或 `helm/` 下创建 Chart，通过 values.yaml 管理环境差异；templates 引用 values，支持 dev/staging/prod 多 values 文件。
   - **理由**：统一管理，减少重复清单，便于版本化与一键部署。

5. **资源 requests/limits**
   - 为各服务设定合理的 CPU/memory requests 与 limits；gate-service 因 WebSocket 连接多，适当提高 memory；game-service 考虑 gRPC 连接池；避免未设 limits 导致节点 OOM。
   - **理由**：保障稳定性与可预测性，便于调度与容量规划。

## Risks / Trade-offs

- **[风险]** 首次部署与现有 docker-compose 环境不一致 → 在 design 与 tasks 中明确端口映射、健康检查、环境变量对应关系；提供迁移检查清单。
- **[权衡]** ConfigMap 过大导致 Pod 启动慢 → 仅注入必要配置项，大配置文件可考虑外部存储或 Init Container 拉取。
- **[风险]** HPA 过于激进导致频繁扩缩 → 设置合理的 cooldown（scaleDown/scaleUp 稳定窗口）、minReplicas 避免缩至 0。

## Migration Plan

- **实现顺序**：先创建基础 Deployment/Service/ConfigMap（Redis → center-service → login-service → game-service → gate-service，按依赖顺序）→ 添加 HPA → 可选 Helm Chart 封装 → 文档与示例 values。
- **部署**：在 dev 环境先行验证；使用 kubectl apply 或 helm install；确认健康检查、服务发现、Redis 连通性正常。
- **回滚**：通过 kubectl rollout undo 或 helm rollback 回退到上一版本；保留原有 docker-compose 作为备用部署方式。

## Open Questions

- Redis 是否使用 StatefulSet 以支持持久化？若仅做缓存，Deployment 即可；若需数据持久化，需 PV/PVC 与 StatefulSet。
- 是否需要 Namespace 隔离（dev/staging/prod）？建议每个环境独立 Namespace。

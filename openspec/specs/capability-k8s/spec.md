# capability-k8s Specification

## Purpose
TBD - created by archiving change add-k8s-deployment. Update Purpose after archive.
## Requirements
### Requirement: K8s 部署清单

系统 SHALL 为 gate、game、login、center、Redis 等所有服务提供 Deployment、Service、ConfigMap 清单；SHALL 定义资源请求（requests）与限制（limits）；MUST 通过环境变量或 ConfigMap 支持多环境差异化配置。

#### Scenario: 部署核心服务至 K8s 集群

- **WHEN** 用户使用 kubectl 或 Helm 将 gate 服务部署至集群
- **THEN** gate 以 Deployment 形式运行，并暴露 ClusterIP/LoadBalancer Service
- **AND** 配置通过 ConfigMap 或 Secret 注入，敏感信息不硬编码

#### Scenario: 资源配置约束生效

- **WHEN** Pod 因负载升高请求更多 CPU/内存
- **THEN** 资源用量被 limits 约束，超出时 Pod 可能被限流或 OOMKilled
- **AND** requests 确保调度时预留资源，避免节点过载

#### Scenario: 多环境部署

- **WHEN** 同一清单部署至 dev/staging/prod 环境
- **THEN** 通过不同命名空间或不同的 ConfigMap 值区分环境
- **AND** Redis、中心服务地址等按环境正确指向对应实例

### Requirement: HPA 水平扩缩容

系统 SHALL 为有状态或高并发服务提供 HorizontalPodAutoscaler；MUST 基于 CPU 或自定义指标（如连接数）触发扩缩；SHALL 定义合理 minReplicas 与 maxReplicas。

#### Scenario: CPU 高负载触发扩容

- **WHEN** gate 服务平均 CPU 使用率超过目标阈值（如 70%）
- **THEN** HPA 自动增加 Pod 副本数
- **AND** 副本数不超过 maxReplicas 定义的上限

### Requirement: 可选 Helm Chart

系统 MAY 提供 Helm Chart 封装 Deployment、Service、ConfigMap、HPA；SHALL 支持通过 values.yaml 覆盖镜像、副本数、资源配置等；MUST 允许不同环境使用不同的 values 文件。

#### Scenario: 使用 Helm 一键部署

- **WHEN** 执行 `helm install gate-service ./chart -f prod-values.yaml`
- **THEN** 所有 K8s 资源按 values 正确渲染并创建
- **AND** 各环境可使用 dev-values.yaml、prod-values.yaml 等差异化配置


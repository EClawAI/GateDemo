# add-k8s-deployment 任务清单

## 1. 目录结构与基础清单

- [ ] 1.1 创建 deploy/k8s/ 或 k8s/ 目录，按服务分子目录（gate-service、game-service、login-service、center-service、redis）
- [ ] 1.2 为 Redis 创建 deployment.yaml、service.yaml、configmap.yaml（若需配置）
- [ ] 1.3 为 center-service 创建 deployment.yaml、service.yaml、configmap.yaml
- [ ] 1.4 为 login-service 创建 deployment.yaml、service.yaml、configmap.yaml
- [ ] 1.5 为 game-service 创建 deployment.yaml、service.yaml、configmap.yaml
- [ ] 1.6 为 gate-service 创建 deployment.yaml、service.yaml、configmap.yaml

## 2. Deployment 配置

- [ ] 2.1 各 Deployment 配置 image、imagePullPolicy、端口与容器端口映射
- [ ] 2.2 配置 livenessProbe 与 readinessProbe（HTTP /health 或 grpc 健康检查），与各服务实际能力一致
- [ ] 2.3 从 ConfigMap 注入环境变量或配置文件（envFrom 或 env）
- [ ] 2.4 配置 resources.requests 与 resources.limits（CPU、memory），按服务特性差异化

## 3. Service 与 ConfigMap

- [ ] 3.1 各 Service 暴露正确端口，selector 与 Deployment 匹配
- [ ] 3.2 ConfigMap 提取 application.yml 关键非敏感项（Redis host、gRPC 端口、心跳间隔等）
- [ ] 3.3 支持通过 namespace 或 ConfigMap 名称区分 dev/staging/prod
- [ ] 3.4 文档说明各配置项含义与推荐值

## 4. HPA 弹性伸缩

- [ ] 4.1 为 gate-service、game-service、login-service、center-service 创建 HPA 清单
- [ ] 4.2 配置 targetCPUUtilizationPercentage、targetMemoryUtilizationPercentage（如 70%）
- [ ] 4.3 设定 minReplicas、maxReplicas
- [ ] 4.4 验证 HPA 与 metrics-server 兼容（若集群已安装）

## 5. Helm Chart（可选）

- [ ] 5.1 创建 Helm Chart 结构（Chart.yaml、values.yaml、templates/）
- [ ] 5.2 将 Deployment、Service、ConfigMap、HPA 模板化，使用 {{ .Values.xxx }} 占位
- [ ] 5.3 提供 values-dev.yaml、values-staging.yaml、values-prod.yaml 示例
- [ ] 5.4 编写 README 说明 helm install/upgrade/rollback 用法

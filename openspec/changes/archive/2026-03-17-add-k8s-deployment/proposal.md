## Why

当前存在以下问题：
- 无 Kubernetes manifests，无法在 K8s 集群上部署服务
- 缺乏弹性伸缩能力，无法应对流量波动
- 配置与部署环境耦合，不利于多环境管理

## What Changes

创建 Kubernetes 部署清单与可选 Helm Chart：
- 创建 Deployment、Service、ConfigMap、HPA 清单
- 配合 Helm Chart 管理多环境（dev/staging/prod）
- 支持水平扩容与配置注入

## 核心功能

1. **Kubernetes Deployment/Service 清单**
   - 各服务 Deployment 配置
   - Service 暴露与网络发现
   - 资源请求与限制（requests/limits）

2. **ConfigMap 配置管理**
   - 外部化配置（application.yml 关键项）
   - 多环境 ConfigMap 分离

3. **HPA 弹性伸缩**
   - 基于 CPU/内存的 HorizontalPodAutoscaler
   - 配置最小/最大副本数

4. **Helm Chart（可选）**
   - 统一管理全部服务 manifests
   - values.yaml 支持环境差异化
   - 一键部署/升级

## Impact

- 影响全部服务（k8s/ 或 deploy/k8s/ 目录）
- 新增 K8s manifests 与可选 Helm 目录

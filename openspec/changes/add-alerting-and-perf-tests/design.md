# 告警与性能测试 (design.md)

## Context

- **当前状态**：系统无 Prometheus 告警规则，连接数暴增、gRPC 错误率飙升、心跳失败、Redis 不可达等异常无法及时发现。无 JMH 微基准测试与 Gatling 端到端压测，单 Gate 最大连接数、消息流转 QPS 等核心指标未知，无法支撑容量规划与性能优化。
- **问题**：运维缺乏告警能力，故障发现滞后；开发与架构缺乏性能数据，优化与扩容决策缺乏依据。
- **约束**：不使用 Spring Boot Actuator；Prometheus 指标暴露方式与现有 gate-service（Netty）、game-service（gRPC）保持一致；JMH 与 Gatling 为独立模块或脚本，不侵入业务代码。

## Goals / Non-Goals

**Goals:**
- 定义 Prometheus alerting rules：连接数阈值、gRPC 错误率、心跳失败率、Redis 延迟/不可达
- 配置 Alertmanager 通知渠道（邮件/钉钉/Slack 等）
- 使用 JMH 微基准测试：消息编解码、核心数据结构吞吐量
- 使用 Gatling 端到端压测：WebSocket 连接、消息流转 QPS、单 Gate 最大连接数摸底

**Non-Goals:**
- 不实现完整的可观测性栈（如 Grafana、Loki，由 add-observability-stack 负责）
- 不改变各服务既有协议或业务逻辑
- 不引入 Spring Boot 或 Spring Cloud 组件

## Decisions

1. **告警规则组织**
   - 在 `deploy/prometheus/` 或 `monitoring/` 下创建 `alerts/` 目录，按能力分文件（如 gate_alerts.yaml、grpc_alerts.yaml、redis_alerts.yaml）；使用 Prometheus rule 格式；通过 Prometheus 的 rule_files 加载。
   - **理由**：模块化，便于按服务维护与复用。

2. **告警指标来源**
   - 连接数、gRPC 错误率、心跳失败率依赖各服务暴露的 Prometheus 指标；gate-service 需在 Netty 或业务层暴露连接数、心跳相关 metric；game-service 需暴露 gRPC 调用计数与错误；Redis 通过 redis_exporter 或应用侧 Redis 操作耗时。
   - **理由**：告警依赖指标，需先有指标暴露（可并行或依赖 add-observability-stack）。

3. **Alertmanager 配置**
   - 配置 receivers（邮件/钉钉/Slack webhook）；route 分组、抑制、静默；按严重程度（critical/warning）路由到不同渠道。
   - **理由**：减少告警风暴，确保关键告警及时送达。

4. **JMH 基准测试**
   - 在 gate-service 或独立 `benchmarks/` 模块中引入 JMH，编写消息编解码（protobuf/JSON）、核心数据结构（如连接映射、会话缓存）吞吐量基准；通过 `mvn exec` 或单独 main 执行。
   - **理由**：微基准可定位热点，指导优化；不与 Spring 耦合。

5. **Gatling 压测**
   - 在 `perf-tests/` 或 `gatling/` 目录创建 Scala/Java Gatling 脚本；模拟 WebSocket 连接、登录、消息收发；测量 QPS、延迟、单 Gate 最大连接数；可依赖 player-client 或独立压测客户端。
   - **理由**：端到端压测贴近真实负载，为容量规划提供数据。

## Risks / Trade-offs

- **[风险]** 指标未暴露则告警无效 → 本变更可与指标暴露并行，或明确依赖；若暂无指标，可先用黑盒探测（如 HTTP/gRPC 健康检查失败告警）作为过渡。
- **[权衡]** JMH 基准受 JVM 预热影响 → 设置足够 warmup 与 measurement 迭代，文档说明执行环境与参数。
- **[风险]** 压测影响线上 → Gatling 仅用于测试环境或隔离集群，不在生产执行；文档明确禁止。

## Migration Plan

- **实现顺序**：先定义告警规则（含占位或模拟指标）→ 配置 Alertmanager → 添加 JMH 基准模块 → 编写 Gatling 脚本 → 执行基准与压测，记录基线数据。
- **部署**：告警规则与 Alertmanager 配置通过 ConfigMap 或 Helm 注入 Prometheus/Alertmanager；JMH 与 Gatling 在 CI 或本地执行，产出报告。
- **回滚**：移除或禁用告警规则、receiver 即可；JMH/Gatling 为只读测试，无运行时影响。

## Open Questions

- Prometheus 与各服务 metrics 暴露是否已在 add-observability-stack 中规划？若否，需在本变更中补充 gate-service、game-service 的指标暴露实现。
- 钉钉/Slack webhook 等敏感配置如何管理？建议使用 Secret 或环境变量，不提交到仓库。

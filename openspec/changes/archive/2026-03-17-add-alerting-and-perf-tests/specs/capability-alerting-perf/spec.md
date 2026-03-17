# capability-alerting-perf (Delta)

## Purpose

通过 Prometheus 告警与 JMH/Gatling 性能测试，提升监控可见性与系统可靠性。

## ADDED Requirements

### Requirement: Prometheus 告警规则

系统 SHALL 提供 Prometheus 告警规则，覆盖连接数异常、gRPC 错误率、心跳失败率、Redis 延迟等指标；MUST 定义合理的告警阈值与持续时间；SHALL 通过 Alertmanager 配置通知渠道（如邮件、Slack、Webhook）。

#### Scenario: gRPC 错误率超标告警

- **WHEN** gate 调用 game 的 gRPC 错误率在 5 分钟内持续超过 5%
- **THEN** Prometheus 触发告警并推送到 Alertmanager
- **AND** Alertmanager 将告警发送至配置的通知渠道

#### Scenario: 心跳失败率过高

- **WHEN** gate 与 game 的心跳失败率超过 10%
- **THEN** 告警触发并标明受影响的实例
- **AND** 运维可通过告警信息快速定位网络或服务异常

#### Scenario: Redis 延迟异常

- **WHEN** Redis 命令平均延迟超过 100ms
- **THEN** 触发 Redis 延迟告警
- **AND** 可与连接数、错误率等告警组合排查问题

### Requirement: JMH 编解码微基准测试

系统 SHALL 为关键编解码逻辑（如 Protobuf、自定义序列化）提供 JMH 微基准测试；MUST 产出吞吐量与延迟指标；SHALL 在 CI 或定期任务中执行，用于防止性能回归。

#### Scenario: 编解码吞吐量回归检测

- **WHEN** 开发修改编解码实现后执行 JMH 基准
- **THEN** 输出 encode/decode 的 ops/sec 与 p99 延迟
- **AND** 若性能下降超过阈值，CI 标记为失败或告警

### Requirement: Gatling WebSocket 端到端负载测试

系统 SHALL 使用 Gatling 对 WebSocket 连接与消息收发进行端到端负载测试；MUST 模拟并发连接、消息频率与业务场景；SHALL 产出响应时间、成功率、吞吐量报告。

#### Scenario: 高并发 WebSocket 连接压测

- **WHEN** 执行 Gatling 场景，模拟 1000 并发用户建立 WebSocket 并收发消息
- **THEN** 生成 HTML 报告，包含响应时间分布与成功率
- **AND** 失败率超过 1% 或 p99 延迟超标时，可视为不通过

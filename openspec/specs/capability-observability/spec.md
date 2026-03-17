# capability-observability Specification

## Purpose
TBD - created by archiving change add-observability-stack. Update Purpose after archive.
## Requirements
### Requirement: Prometheus 指标暴露

系统 SHALL 在 gate-service 中通过自定义 Netty HTTP 端点暴露 GET `/metrics`；MUST 返回 Prometheus 文本格式（text/plain）；SHALL 使用 micrometer-registry-prometheus 提供 MeterRegistry。

#### Scenario: 请求 /metrics 返回 Prometheus 格式
- **WHEN** 客户端向 gate-service 的 metrics 端点发送 GET `/metrics`
- **THEN** 返回 200 且 Content-Type 为 text/plain
- **AND** 响应体为 Prometheus exposition format（如 `# TYPE ... # HELP ...`）

#### Scenario: 指标包含基础数据
- **WHEN** /metrics 被访问
- **THEN** 至少包含 JVM 或自定义业务指标（如连接数、消息数）
- **AND** 指标格式符合 Prometheus 解析规范

### Requirement: OpenTelemetry 分布式链路追踪

系统 SHALL 引入 OpenTelemetry Java SDK 与 Zipkin Exporter；SHALL 通过 gRPC Client/Server Interceptor 传播 traceId 与 spanId；跨服务调用时 MUST 将 Trace Context 注入 gRPC metadata 或等效机制。

#### Scenario: gRPC 调用传播 TraceId
- **WHEN** Gate 向 Game 发起 gRPC 调用
- **THEN** gRPC 请求 metadata 中包含 traceparent 或 W3C Trace Context
- **AND** Game 端可提取并延续同一 trace

#### Scenario: Zipkin 导出 Span
- **WHEN** 配置了 Zipkin endpoint 且 Span 完成
- **THEN** Span 数据被导出到 Zipkin（或配置的 endpoint）
- **AND** Zipkin UI 可查看完整调用链路

### Requirement: 日志包含 TraceId/SpanId

系统 SHALL 将 OpenTelemetry 的 traceId、spanId 写入日志上下文（如 MDC）；SHALL 在 logback 配置中使日志输出包含 traceId 与 spanId 字段。

#### Scenario: 日志输出包含 TraceId
- **WHEN** 在存在 Span 的上下文中记录日志
- **THEN** 日志行或 JSON 中包含 traceId 与 spanId
- **AND** 可通过 traceId 关联同一请求的所有日志

### Requirement: logback 按环境区分格式

系统 SHALL 通过 logback 配置支持多环境；prod 环境 MUST 使用 JSON 格式输出（如 logstash-logback-encoder）；dev 环境 SHALL 使用可读文本格式（PatternLayout）。

#### Scenario: prod 环境 JSON 日志
- **WHEN** 应用以 prod 环境启动且 logback 配置生效
- **THEN** 日志输出为 JSON 格式
- **AND** 包含 timestamp、level、logger、message、traceId、spanId 等字段

#### Scenario: dev 环境文本日志
- **WHEN** 应用以 dev 环境启动
- **THEN** 日志输出为可读文本格式
- **AND** 便于本地调试

### Requirement: MetricsCollector 迁移至 Micrometer

系统 SHALL 将现有手写 MetricsCollector 的指标逻辑迁移到 Micrometer Counter/Gauge/Timer；SHALL 废弃或移除不再使用的 MetricsCollector；新指标 MUST 通过 Prometheus 端点暴露。

#### Scenario: 业务指标迁移
- **WHEN** 原有 MetricsCollector 统计的指标（如消息数、连接数）有对应 Micrometer 实现
- **THEN** /metrics 端点中可见等价指标
- **AND** 原有 MetricsCollector 不再被业务代码调用


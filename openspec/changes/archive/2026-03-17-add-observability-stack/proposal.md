## Why

gate-service 存在以下可观测性问题：
- 有手写 MetricsCollector 未使用 Micrometer，未暴露 HTTP 端点
- TraceIdGenerator 仅本地使用，未跨服务传播
- 日志为纯文本，不便于 ELK 解析

## What Changes

添加完整可观测性技术栈：
- 添加 micrometer-registry-prometheus 依赖，通过自定义 HTTP 端点暴露 Prometheus 指标
- 引入 OpenTelemetry Java SDK + Zipkin Exporter + gRPC Interceptor 传播 TraceId
- 添加 logback.xml 配置，prod 环境用 JSON 格式（logstash-logback-encoder）、dev 环境用可读文本格式

## 核心功能

1. **Micrometer + Prometheus 指标暴露**
   - 集成 micrometer-registry-prometheus
   - 通过自定义 Netty HTTP 端点暴露 `/metrics`（复用 P001 的健康检查端口）

2. **分布式链路追踪 (OpenTelemetry + Zipkin)**
   - 引入 OpenTelemetry Java SDK
   - gRPC Interceptor 传播 TraceId/SpanId
   - 跨服务链路串联

3. **结构化日志 (logback JSON)**
   - prod 环境 JSON 格式便于 ELK 解析
   - dev 环境保留可读文本格式

## Impact

- 影响 `gate-service` MetricsCollector 替换、TraceId 传播、logback 配置
- 影响 `game-service` TraceId 传播、logback 配置
- 影响 `login-service` TraceId 传播、logback 配置

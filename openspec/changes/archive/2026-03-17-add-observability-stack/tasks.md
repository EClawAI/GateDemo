## 1. Micrometer + Prometheus 指标暴露

- [x] 1.1 添加 micrometer-core、micrometer-registry-prometheus 依赖到 gate-service
- [x] 1.2 在 gate-service 启动时初始化 PrometheusMeterRegistry
- [x] 1.3 在 Netty 健康端口（或独立 metrics 端口）增加 GET `/metrics` 路由，调用 registry.scrape() 并返回 Prometheus 文本格式
- [x] 1.4 配置 Content-Type: text/plain；支持 Prometheus 拉取

## 2. OpenTelemetry + Zipkin + gRPC Interceptor

- [x] 2.1 添加 OpenTelemetry Java SDK、opentelemetry-exporter-zipkin、opentelemetry-api 等依赖到 gate-service、game-service
- [ ] 2.2 实现 gRPC Client Interceptor：从 Context 提取 SpanContext，注入 traceparent 到 gRPC metadata
- [ ] 2.3 实现 gRPC Server Interceptor：从 gRPC metadata 提取 traceparent，创建/延续 Span
- [ ] 2.4 配置 Zipkin Exporter endpoint（可通过配置项，如 otel.exporter.zipkin.endpoint）；应用启动时初始化 TracerProvider 与 Exporter
- [ ] 2.5 将 traceId、spanId 写入 MDC，供 logback 使用

## 3. logback 多环境配置

- [x] 3.1 添加 logstash-logback-encoder 依赖
- [x] 3.2 创建或更新 logback.xml，通过系统属性（如 env）区分 prod/dev
- [x] 3.3 prod：配置 JsonLayout 或 LogstashEncoder，输出包含 traceId、spanId 的 JSON
- [x] 3.4 dev：配置 PatternLayout，保持可读文本格式
- [x] 3.5 确保 MDC 中的 traceId、spanId 在 pattern 或 JSON 中输出

## 4. MetricsCollector 迁移

- [ ] 4.1 将 MetricsCollector 中的计数逻辑改为使用 Micrometer Counter/Gauge/Timer
- [ ] 4.2 在业务代码中替换 MetricsCollector 调用为 Micrometer API
- [ ] 4.3 移除或废弃 MetricsCollector 类

## 5. 多服务适配与文档

- [ ] 5.1 在 game-service 中集成 gRPC Server Interceptor 以接收并延续 Trace
- [ ] 5.2 在 login-service 中集成 Trace 传播（若使用 HTTP，可添加 header 传播）
- [ ] 5.3 为 game-service、login-service 添加 logback 多环境配置（若尚未存在）
- [ ] 5.4 在 README 或运维文档中说明 /metrics 端点、Zipkin 配置、日志格式及环境变量

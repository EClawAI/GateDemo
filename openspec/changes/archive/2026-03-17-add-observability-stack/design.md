# 可观测性技术栈 (add-observability-stack)

## Context

- **当前状态**：gate-service 有手写 MetricsCollector（内存计数 + 周期日志输出），未使用 Micrometer，未暴露 HTTP 端点；TraceIdGenerator 基于 ThreadLocal 仅本地使用，未在 gRPC 调用间跨服务传播；日志为纯文本，不便于 ELK 解析。
- **问题**：无标准 Prometheus 指标暴露，无法接入 Grafana 等监控；TraceId 无法串联 Gate→Game→Login 等跨服务请求；日志格式不利于集中采集与检索。
- **约束**：不使用 Spring Boot Actuator 或 Spring Cloud；gate-service 基于 Netty，需通过自定义 HTTP 端点暴露指标；与 P001 健康检查端口（如 8890）可复用或独立配置。

## Goals / Non-Goals

**Goals:**

- 添加 micrometer-registry-prometheus 依赖，通过自定义 Netty HTTP 端点暴露 `/metrics`（Prometheus 文本格式）。
- 引入 OpenTelemetry Java SDK + Zipkin Exporter，通过 gRPC Interceptor 传播 TraceId/SpanId，实现跨服务链路串联。
- 添加 logback.xml 配置，prod 环境输出 JSON 格式（logstash-logback-encoder），dev 环境保持可读文本格式。

**Non-Goals:**

- 不在此 change 中部署 Zipkin、Prometheus、Grafana 等基础设施；仅完成 SDK 集成与数据输出。
- 不迁移所有业务指标到 Micrometer（可逐步迁移，先暴露 JVM、连接数等基础指标）。
- 不实现分布式 Trace 的采样策略（可使用默认全部采样，后续可调优）。

## Decisions

1. **/metrics 与健康检查共用 Netty HTTP 端口**
   - 在 gate-service 的 Netty 健康端口（如 8890）上增加 GET `/metrics` 路由；返回 `PrometheusRegistry.scrape()` 的文本输出，Content-Type 为 `text/plain`。
   - **理由**：复用 P001 已有 HTTP 服务器，减少端口占用；与健康检查职责相近（均为运维接口），可统一管理。

2. **Micrometer 替换 MetricsCollector**
   - 引入 `micrometer-registry-prometheus`，使用 `PrometheusMeterRegistry`；将现有 MetricsCollector 的计数逻辑迁移为 `Counter`、`Gauge`、`Timer` 等；废弃或移除手写 MetricsCollector。
   - **理由**：标准生态，便于与 Prometheus 对接；支持多维度、多类型指标。

3. **OpenTelemetry + Zipkin 替代 TraceIdGenerator**
   - 引入 OpenTelemetry Java SDK、`opentelemetry-exporter-zipkin`；在 gRPC Client 与 Server 两侧添加 Interceptor，从 Context 提取/注入 `traceparent` 或 W3C Trace Context；使用 `Span.current().getSpanContext()` 获取 traceId/spanId，并写入 MDC 供 logback 输出。
   - **理由**：标准化 Trace 格式，支持 Zipkin/Jaeger 等后端；gRPC Interceptor 是标准扩展点。

4. **logback 按环境区分配置**
   - 通过 `logback-spring.xml` 或 `logback.xml` + 系统属性（如 `env=prod`）区分环境；prod 使用 `logstash-logback-encoder` 输出 JSON；dev 使用 `PatternLayout` 保持可读文本。
   - **理由**：ELK 等系统偏好 JSON；开发调试需要可读性；同一代码库满足多环境。

5. **TraceId 与日志集成**
   - OpenTelemetry 的 traceId/spanId 写入 MDC（Mapped Diagnostic Context）；logback pattern 或 JSON encoder 中引用 `traceId`、`spanId` 字段。
   - **理由**：日志与 Trace 关联，便于排查；无需保留两套 TraceId 机制。

## Risks / Trade-offs

- **[风险]** OpenTelemetry 与现有 TraceIdGenerator 并存可能导致混乱 → mitigation：逐步迁移，先在新路径使用 OTel，旧 TraceIdGenerator 可保留为 fallback 或移除。
- **[权衡]** Zipkin Exporter 需配置 endpoint URL → 通过配置（如 `otel.exporter.zipkin.endpoint`）指定，默认可指向本地 Zipkin；未配置时可不初始化 Exporter，避免启动失败。
- **[风险]** logstash-logback-encoder 增加依赖体积 → 仅 prod 使用，dev 不加载；可接受的 trade-off。

## Migration Plan

- **实现顺序**：1) 添加 micrometer + prometheus 依赖，实现 /metrics 端点；2) 引入 OpenTelemetry + Zipkin + gRPC Interceptor；3) 配置 logback 多环境；4) 迁移 MetricsCollector 到 Micrometer；5) 更新 gate-service、game-service、login-service 的依赖与配置。
- **部署**：需配置 Zipkin 地址（若使用）；无数据迁移；先部署 Gate，观察 /metrics 与日志输出。
- **回滚**：移除 OTel 与 Zipkin 初始化、恢复原 logback 配置；/metrics 端点可保留或移除，不影响核心业务。

## Open Questions

- Zipkin 未部署时，OTel 是否仍应初始化？建议：Exporter 可配置为 no-op 或内存 buffer，避免阻塞；或仅在有 endpoint 时启用。
- game-service、login-service 是否也需暴露 /metrics？本 change 可仅覆盖 gate-service，其余服务后续迭代。

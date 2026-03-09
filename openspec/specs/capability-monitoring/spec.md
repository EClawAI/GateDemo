# 监控能力

## 概述

监控能力提供TraceId追踪、消息日志和性能指标收集，便于问题排查和性能优化。

## 需求

### REQUIREMENT: TraceId生成

系统 SHALL 为每个请求生成唯一的TraceId。

### REQUIREMENT: TraceId传递

系统 SHALL 将TraceId贯穿整个请求生命周期。

### REQUIREMENT: 消息日志

系统 SHALL 记录每个消息的收发日志。

### REQUIREMENT: 性能指标

系统 SHALL 收集在线玩家数、QPS等性能指标。

## 场景

### Scenario: 消息追踪

- **GIVEN** 客户端发送请求
- **WHEN** 请求进入系统
- **THEN** 生成TraceId
- **AND** 日志中记录TraceId

### Scenario: 性能监控

- **GIVEN** 系统运行中
- **WHEN** 定时收集指标
- **THEN** 记录在线人数、消息数等

## 实现

- **TraceId生成器**: `log/TraceIdGenerator`
- **消息日志**: `log/MessageLogger`
- **指标收集器**: `monitor/MetricsCollector`

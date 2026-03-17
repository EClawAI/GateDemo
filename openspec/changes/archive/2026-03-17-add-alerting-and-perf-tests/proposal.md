## Why

当前存在以下缺口：
- 无告警机制，连接数暴增、错误率飙升、Redis 不可达等场景无法及时发现
- 无性能测试，不清楚单 Gate 最大连接数和 QPS 上限
- 无法为容量规划与优化提供数据支撑

## What Changes

建立告警体系与性能测试能力：
- 定义 Prometheus alerting rules（连接数阈值、gRPC 错误率、心跳失败率、Redis 延迟）
- 配置 Alertmanager 通知渠道（邮件/钉钉/Slack 等）
- 使用 JMH 微基准测试（编解码等热点路径）+ Gatling 端到端压测

## 核心功能

1. **Prometheus 告警规则**
   - 连接数超阈值告警
   - gRPC 错误率告警
   - 心跳失败率告警
   - Redis 不可达/延迟告警

2. **Alertmanager 通知**
   - 配置告警接收方
   - 告警分组与抑制策略

3. **JMH 微基准测试**
   - 消息编解码性能
   - 核心数据结构吞吐量

4. **Gatling 压力测试**
   - WebSocket 连接压测
   - 端到端消息流转 QPS
   - 单 Gate 最大连接数摸底

## Impact

- 影响运维配置（Prometheus/Alertmanager）
- 影响 gate-service（JMH 用例）
- 影响 player-client 或压测客户端（Gatling 脚本）

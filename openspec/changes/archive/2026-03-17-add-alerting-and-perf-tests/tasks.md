# P018: 告警与性能测试

## 1. Prometheus 告警规则

- [ ] 1.1 在 deploy/prometheus/ 或 monitoring/ 下创建 alerts/ 目录
- [ ] 1.2 定义 gate_alerts.yaml：连接数超过阈值、WebSocket 连接暴增
- [ ] 1.3 定义 grpc_alerts.yaml：gRPC 错误率升高、调用失败
- [ ] 1.4 定义 heartbeat_alerts.yaml：心跳失败率异常
- [ ] 1.5 定义 redis_alerts.yaml：Redis 延迟升高、不可达
- [ ] 1.6 配置 Prometheus 的 rule_files 加载上述规则；按严重程度区分 critical/warning

## 2. Alertmanager 通知

- [ ] 2.1 配置 Alertmanager receivers：邮件、钉钉、Slack webhook 等
- [ ] 2.2 配置 route：按告警分组、抑制、静默
- [ ] 2.3 按严重程度路由到不同通知渠道
- [ ] 2.4 敏感配置（webhook URL 等）使用 Secret 或环境变量，不提交到仓库

## 3. JMH 微基准测试

- [ ] 3.1 引入 JMH 依赖，在 gate-service 或 benchmarks/ 模块创建基准测试
- [ ] 3.2 编写消息编解码基准：protobuf 序列化/反序列化、JSON 编解码
- [ ] 3.3 编写核心数据结构基准：连接映射、会话缓存吞吐量
- [ ] 3.4 配置足够 warmup 与 measurement 迭代；文档说明执行环境与参数
- [ ] 3.5 通过 mvn exec 或单独 main 执行，产出报告

## 4. Gatling 负载压测

- [ ] 4.1 在 perf-tests/ 或 gatling/ 目录创建 Gatling 脚本（Scala/Java）
- [ ] 4.2 模拟 WebSocket 连接建立、登录、消息收发
- [ ] 4.3 测量 QPS、延迟 P99、单 Gate 最大连接数
- [ ] 4.4 执行压测并记录基线数据，用于容量规划
- [ ] 4.5 文档明确：Gatling 仅用于测试环境或隔离集群，禁止在生产执行

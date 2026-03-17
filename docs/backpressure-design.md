# Backpressure Design

本文档描述 GateDemo 中 gRPC Stream 与 Redis Stream 的背压（flow control）设计。

## 1. gRPC Stream 背压

### 1.1 机制

- gRPC 基于 HTTP/2，内置 flow control
- 客户端/服务端应遵循 `onReady` 语义：仅在流就绪时发送数据，避免发送端压倒接收端

### 1.2 Gate 与 Game 的 gRPC 双向流

- **Gate → Game**：Gate 作为客户端，向 Game 发送上行消息
- **Game → Gate**：Game 作为服务端，向 Gate 推送下行消息

### 1.3 实现要点

- 在 gate-service 的 gRPC Stream 调用处集成 `onReady` 回调
- 发送速率控制：仅在 `onReady == true` 时发送，或配合批量/间隔配置
- 在 game-service 的 gRPC Stream 服务端（若为流式）同样遵循背压语义，避免无界发送

### 1.4 配置项

- **批量大小**：每次 `onReady` 触发时最多发送的消息条数
- **发送间隔**：两次发送之间的最小间隔（ms）
- 便于在生产环境根据负载调优

### 1.5 行为说明

- 当接收端处理不过来时，流会进入「未就绪」状态，发送端应暂停
- 待接收端消化后，`onReady` 再次为 true，发送恢复

---

## 2. Redis Stream 背压

### 2.1 场景

- Gate 将消息写入 Redis Stream，供 Game 或下游消费
- 若消费者处理慢，Stream 中 pending 消息堆积，可能导致内存与延迟问题

### 2.2 监控

- 使用 `XPENDING` 或等效 API 监控 Stream 的 pending 消息数量
- 定义 `pending` 阈值配置项（如 `redis.stream.pending.threshold=10000`）

### 2.3 控制逻辑

- 在 Gate 向 Redis Stream 生产消息**之前**检查 pending 数量
- 超阈值时：暂停生产或进入排队模式，避免进一步堆积
- 实现定期或事件驱动的 pending 检查，pending 下降后恢复生产

### 2.4 告警

- 超阈值时记录告警或 WARN 日志，便于运维介入

### 2.5 调优建议

- 根据消费者吞吐量与 RTO 确定阈值
- 可配合 consumer group 的 `XACK` 速度与 `XAUTOCLAIM` 策略调整

# capability-key-backpressure-backup (Delta)

## Purpose

通过密钥管理、流控与持久化备份能力，提升系统安全性和可靠性。

## ADDED Requirements

### Requirement: 密钥管理与版本化轮换

系统 SHALL 集成 Vault 或 KMS 进行密钥存储与获取；MUST 支持密钥版本化，新旧版本可并存以支持平滑轮换；SHALL 在应用层通过版本标识获取对应密钥，轮换时无需停机。

#### Scenario: 密钥轮换无停机

- **WHEN** 运维在 Vault/KMS 中发布新版本密钥
- **THEN** 应用可通过版本号或别名获取新密钥
- **AND** 旧版本在一段时间内仍可解密存量数据，新数据使用新版本加密

#### Scenario: 启动时拉取密钥

- **WHEN** 服务启动并需要加密密钥
- **THEN** 从 Vault/KMS 按配置路径拉取密钥
- **AND** 拉取失败时记录告警并可按策略 abort 或使用本地占位值

### Requirement: gRPC Stream 背压

系统 SHALL 在 gRPC 流式调用中实现背压控制；MUST 利用 StreamObserver 的 onReady 回调判断下游是否可接收数据；SHALL 在 onReady 为 false 时暂停或限速发送，避免内存积压与 OOM。

#### Scenario: 下游消费慢时限流发送

- **WHEN** gRPC Stream 下游处理缓慢，onReady 返回 false
- **THEN** 发送方暂停或降速发送消息
- **AND** 当 onReady 再次为 true 时恢复发送，保持流控稳定

### Requirement: Redis Stream 背压与 Pending 监控

系统 SHALL 对 Redis Stream 消费者实现背压机制；MUST 监控 Pending 消息数量与空闲时长；SHALL 当 Pending 积压或消息空闲超时超过阈值时，触发告警或自动限流。

#### Scenario: Pending 积压告警

- **WHEN** Redis Stream consumer group 的 pending 消息数超过 1000
- **THEN** 触发背压告警或限流新消息消费
- **AND** 运维可据此排查消费者阻塞或扩容

#### Scenario: 空闲消息超时处理

- **WHEN** 某条消息在 pending 状态超过配置的 idle 阈值
- **THEN** 系统记录并可选地将其移入死信或重试
- **AND** 避免单条消息阻塞整个 stream 处理

### Requirement: Redis 持久化与 DB 备份

系统 SHALL 配置 Redis RDB 和/或 AOF 持久化；MUST 定义 RDB 快照频率与 AOF 策略；SHALL 制定 DB 备份策略，包括全量与增量、保留周期、恢复演练。

#### Scenario: Redis 故障恢复

- **WHEN** Redis 实例异常重启
- **THEN** 从最近的 RDB 或 AOF 文件恢复数据
- **AND** 按配置的持久化策略，数据丢失窗口在可接受范围内

#### Scenario: 主库备份策略

- **WHEN** 按备份策略执行（如每日全量、每小时增量）
- **THEN** 备份文件写入指定存储并设置保留周期
- **AND** 定期进行恢复演练，验证备份可用性

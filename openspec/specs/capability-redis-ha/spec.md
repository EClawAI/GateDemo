# capability-redis-ha Specification

## Purpose
TBD - created by archiving change add-redis-ha-and-ttl. Update Purpose after archive.
## Requirements
### Requirement: Redis Sentinel 配置支持

系统 SHALL 在 RedisConfig 中支持 Redis Sentinel 配置；SHALL 使用 Lettuce 原生 API 连接 Sentinel；当配置了 redis.sentinel.master 与 redis.sentinel.nodes 时 MUST 使用 Sentinel 模式，否则使用单节点模式；MUST 在 Sentinel 模式下支持故障自动切换。

#### Scenario: 配置 Sentinel 时使用高可用模式
- **WHEN** 配置了 redis.sentinel.master 与 redis.sentinel.nodes
- **THEN** 系统通过 Sentinel 发现并连接 Redis Master
- **AND** 当 Master 故障时，Lettuce 自动切换至新 Master

#### Scenario: 未配置 Sentinel 时使用单节点
- **WHEN** 未配置 Sentinel 相关项，仅配置 redis.host 与 redis.port
- **THEN** 系统以单节点模式连接 Redis
- **AND** 行为与改造前一致

### Requirement: 离线消息 Key TTL

系统 SHALL 为离线消息相关 Redis key 设置 TTL；TTL MUST 为 7 天（604800 秒）；在写入离线消息时 SHALL 同时设置 TTL；MUST 在 TTL 过期前允许客户端正常拉取。

#### Scenario: 写入离线消息时设置 TTL
- **WHEN** 系统写入离线消息到 Redis
- **THEN** 同时对该 key 执行 EXPIRE 或 SET 时指定 EX 参数，TTL 为 7 天
- **AND** key 在 7 天后自动过期删除

### Requirement: 心跳/实例注册 Key TTL

系统 SHALL 为心跳、实例注册等 Redis key 设置 TTL；TTL MUST 为 2 分钟（120 秒）；SHALL 由心跳逻辑周期性刷新 TTL（如每 30 秒刷新一次）；MUST 在实例下线或心跳停止时，key 在 TTL 到期后自动删除。

#### Scenario: 心跳刷新 TTL
- **WHEN** 实例周期性上报心跳
- **THEN** 每次心跳时刷新该实例对应 key 的 TTL 为 2 分钟
- **AND** 若心跳停止，key 在 2 分钟内过期

#### Scenario: 实例下线后 key 自动剔除
- **WHEN** 实例进程退出，不再发送心跳
- **THEN** 对应 key 在 TTL 到期后由 Redis 自动删除
- **AND** 服务发现或集群管理逻辑将该实例视为下线

### Requirement: Token 与 Session Key TTL

系统 SHALL 为 Token、session 相关 Redis key 设置 TTL；TTL MUST 与业务定义的过期时间同步（如 Token 24 小时过期，则 TTL 设为 24 小时）；在签发或续期时 SHALL 更新 TTL；MUST 确保 key 过期时间与业务过期语义一致。

#### Scenario: Token key TTL 与业务过期同步
- **WHEN** 签发或刷新 Token 时写入 Redis
- **THEN** 设置该 key 的 TTL 等于业务定义的 Token 有效期
- **AND** 过期后 key 自动删除，与业务「Token 失效」一致

### Requirement: maxmemory 与 eviction policy

系统 SHALL 在文档与运维手册中明确推荐 Redis maxmemory 配置；SHALL 推荐 eviction policy 为 volatile-ttl 或 allkeys-lru；MUST 确保所有业务 key 均设有 TTL 或接受 LRU 淘汰，以避免 OOM。

#### Scenario: 内存达到上限时触发淘汰
- **WHEN** Redis 内存使用达到 maxmemory
- **THEN** 按配置的 eviction policy 淘汰 key
- **AND** 应用层需容忍部分 key 被淘汰（通过重试、降级等方式）


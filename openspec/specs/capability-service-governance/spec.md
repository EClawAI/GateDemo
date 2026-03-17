# capability-service-governance Specification

## Purpose
TBD - created by archiving change add-service-governance. Update Purpose after archive.
## Requirements
### Requirement: Nacos 配置中心集成

系统 SHALL 通过 Nacos SDK（非 Spring Cloud）连接 Nacos 配置中心；MUST 支持从 Nacos 拉取配置并覆盖本地默认值；SHALL 通过 Nacos 监听器回调实现配置热加载，无需重启进程。

#### Scenario: 配置变更热生效

- **WHEN** 运维在 Nacos 控制台修改某配置项（如 Redis 连接超时）
- **THEN** 注册的 Nacos 监听器收到变更通知
- **AND** 应用在不重启的前提下应用新配置

#### Scenario: 启动时拉取配置

- **WHEN** gate 服务启动并连接 Nacos
- **THEN** 从指定 DataId/Group 拉取配置并完成初始化
- **AND** 拉取失败时按策略回退到本地默认配置或 abort

### Requirement: Nacos 服务注册与发现

系统 SHALL 使用 Nacos 作为服务注册中心；MUST 支持服务实例注册、健康检查与自动下线；SHALL 提供服务发现 API，按服务名获取可用实例列表，并替换原有硬编码地址。

#### Scenario: 服务启动时注册

- **WHEN** game 服务启动并完成健康检查
- **THEN** 向 Nacos 注册本实例的 IP、端口及元数据
- **AND** 周期性上报心跳，超时未报则 Nacos 将该实例标记为不健康

#### Scenario: 消费端按服务名发现

- **WHEN** gate 需要调用 game 服务
- **THEN** 通过 Nacos 查询服务名为 "game" 的实例列表
- **AND** 从返回的健康实例中选择一个进行调用，无需硬编码 IP:Port

### Requirement: 去除硬编码地址

系统 MUST 移除配置中与服务地址相关的硬编码；SHALL 将所有服务发现依赖统一迁移至 Nacos；MUST 保证在 Nacos 不可用时具备降级或本地缓存策略。

#### Scenario: 地址配置迁移

- **WHEN** 原配置中存在 game.addr=192.168.1.10:9090 等硬编码
- **THEN** 替换为通过 Nacos 服务发现获取 game 实例地址
- **AND** 无 Nacos 时可按策略使用本地配置或快速失败并告警


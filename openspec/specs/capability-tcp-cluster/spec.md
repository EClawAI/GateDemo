# capability-tcp-cluster Specification

## Purpose
TBD - created by archiving change integrate-tcp-and-cluster. Update Purpose after archive.
## Requirements
### Requirement: NettyTcpServer 接入启动流程

系统 SHALL 在 gate-service 启动时将 NettyTcpServer 纳入生命周期；SHALL 通过 Guice 或手动注册方式在适当时机创建并启动 NettyTcpServer；SHALL 根据配置 gate.tcp.enabled 决定是否启动 TCP 服务；MUST 在 gate.tcp.enabled=true 时监听配置的 TCP 端口并接受连接；MUST 在服务关闭时正确释放 TCP 相关资源。

#### Scenario: gate.tcp.enabled=true 时启动 TCP
- **WHEN** gate.tcp.enabled=true 且 gate-service 启动
- **THEN** NettyTcpServer 被初始化并开始监听 TCP 端口
- **AND** 可接受 TCP 客户端连接

#### Scenario: gate.tcp.enabled=false 时不启动 TCP
- **WHEN** gate.tcp.enabled=false
- **THEN** NettyTcpServer 不被创建或启动
- **AND** TCP 端口不占用

### Requirement: Gate 实例注册与心跳

系统 SHALL 在 gate-service 启动后通过 GateClusterManager 将本实例注册到 Redis；SHALL 使用 ScheduledExecutorService 周期性发送心跳，刷新 Redis 中的实例状态；MUST 在实例信息中包含 host、port、instanceId 等足以标识与连接的信息；MUST 在服务关闭时从 Redis 注销或依赖 TTL 自动剔除。

#### Scenario: 启动后注册到 Redis
- **WHEN** gate-service 启动完成
- **THEN** GateClusterManager 将本实例信息写入 Redis
- **AND** 其他服务（如 login-service）可从中读取 Gate 实例列表

#### Scenario: 周期性心跳维持在线状态
- **WHEN** GateClusterManager 已注册
- **THEN** 按配置周期（如 30 秒）执行心跳，刷新 Redis 中该实例的 TTL
- **AND** 若心跳停止，实例在 TTL 过期后被剔除

### Requirement: TCP 消息复用 MessageDispatcher

系统 SHALL 将 TCP 接收到的业务消息经解码后交由 MessageDispatcher 处理；SHALL 复用与 WebSocket 相同的 MessageDispatcher 实现；MUST 保证 TCP 与 WebSocket 消息的处理逻辑一致；MUST 支持 echo、broadcast 等已有消息类型。

#### Scenario: TCP 消息经 MessageDispatcher 处理
- **WHEN** TCP 客户端发送业务消息
- **THEN** NettyTcpServer 解码后调用 MessageDispatcher 分发
- **AND** 处理结果与 WebSocket 路径一致

#### Scenario: 与 WebSocket 共用处理逻辑
- **WHEN** 同一类型消息分别从 TCP 与 WebSocket 接入
- **THEN** 两者均经 MessageDispatcher 处理
- **AND** 业务行为一致

### Requirement: TCP 配置可配置

系统 SHALL 支持通过配置指定 TCP 监听端口；SHALL 支持 gate.tcp.enabled 开关；MUST 在配置缺失或无效时使用合理默认值或明确报错。

#### Scenario: TCP 端口可配置
- **WHEN** 配置 gate.tcp.port=9000
- **THEN** NettyTcpServer 监听 9000 端口
- **AND** 与 WebSocket 端口分离

### Requirement: 关闭时释放资源

系统 SHALL 在 gate-service 关闭时停止 NettyTcpServer 并释放其资源；SHALL 停止 GateClusterManager 的心跳任务；MUST 确保 Redis 中该实例信息在合理时间内被剔除（显式注销或 TTL）。

#### Scenario: 关闭时停止 TCP 与心跳
- **WHEN** gate-service 收到关闭信号
- **THEN** NettyTcpServer 停止接受新连接并关闭
- **AND** GateClusterManager 停止心跳，实例从 Redis 中剔除或等待 TTL 过期


# capability-game-connection-pool (Delta)

## Purpose
Gate 与 Game 之间的 gRPC 连接池能力：统一连接管理、复用、健康检查与自动重连。

## ADDED Requirements

### Requirement: Gate 使用单一连接池与 Game 通信

系统 SHALL 通过唯一连接池组件维护与各 Game 实例的 gRPC 连接；所有需要访问 Game 的调用 MUST 经该连接池获取连接或发送消息，不得在池外另建或维护独立 channel。

#### Scenario: Gate 启动时初始化连接池
- **WHEN** Gate 服务启动且已配置或发现 Game 实例列表
- **THEN** 连接池为每个 Game 实例建立并维护至少一个 gRPC 连接（Channel）
- **AND** 不因单次请求而新建连接

#### Scenario: 消息发送复用连接
- **WHEN** Gate 需向某 Game 实例发送消息
- **THEN** 使用连接池中该实例的已有连接发送，不新建连接

### Requirement: 连接池支持动态增删

系统 SHALL 支持在运行时向连接池添加或移除 Game 实例连接；当使用服务发现时，发现的注册/注销事件 MUST 通过连接池的添加/移除接口生效，连接池为连接存活的唯一来源。

#### Scenario: 发现新 Game 时添加连接
- **WHEN** 服务发现上报新 Game 实例注册
- **THEN** 连接池为该实例建立连接并加入池中
- **AND** 后续请求可复用该连接

#### Scenario: Game 下线时移除连接
- **WHEN** 服务发现上报某 Game 实例下线或注销
- **THEN** 连接池移除该实例连接并释放资源
- **AND** 不再向该实例分配新请求

### Requirement: 连接健康检查与失效处理

系统 SHALL 对池内连接进行定期健康检测（如心跳/Ping）；当检测失败或连接异常时，系统 SHALL 将该连接视为失效并从池中移除，并 SHALL 在适当时机自动尝试重连（或由服务发现再次注册触发）。

#### Scenario: 定期健康检测
- **WHEN** 连接池中存在已建立的连接
- **THEN** 系统按配置周期发送心跳或执行健康探测
- **AND** 未响应或失败的连接被视为失效

#### Scenario: 失效连接移除与重连
- **WHEN** 某连接健康检测失败或发生不可恢复错误
- **THEN** 系统从池中移除该连接并释放资源
- **AND** 系统在后续周期或策略下尝试重新建立该实例连接（或由发现事件触发）

### Requirement: 连接池可配置

系统 SHALL 支持通过配置指定连接池相关参数（如 keepAlive 时间、超时、池大小或每实例连接数等）；未启用服务发现时，SHALL 支持通过静态配置的 Game 列表初始化连接池。

#### Scenario: 静态配置初始化
- **WHEN** 未启用服务发现且已配置 Game 列表
- **THEN** Gate 启动时仅根据该列表初始化连接池
- **AND** 池中仅包含配置中的实例

#### Scenario: 连接参数可配置
- **WHEN** 管理员配置 keepAlive、超时等参数
- **THEN** 新建连接使用该配置
- **AND** 已有连接在实现允许的范围内可沿用或按新配置重建

# 服务发现能力

## ADDED Requirements

### Requirement: Gate服务注册

系统 SHALL 在Gate启动时向ZooKeeper注册自身服务信息。

#### Scenario: Gate启动注册
- **WHEN** Gate服务启动
- **THEN** 在ZooKeeper创建临时节点 `/gate/{gateId}`
- **AND** 节点内容包含 host:port:healthy

### Requirement: Gate服务注销

系统 SHALL 在Gate关闭时从ZooKeeper注销自身服务。

#### Scenario: Gate关闭注销
- **WHEN** Gate服务关闭
- **THEN** 删除ZooKeeper节点 `/gate/{gateId}`
- **AND** 断开与所有Game的连接

### Requirement: Game服务发现

系统 SHALL 能够动态发现可用的Game服务列表。

#### Scenario: 发现Game服务
- **WHEN** Gate启动或收到Game变更通知
- **THEN** 查询ZooKeeper获取所有Game节点
- **AND** 解析节点内容获取 host:port

### Requirement: 动态连接管理

系统 SHALL 根据服务发现结果动态建立/断开与Game的连接。

#### Scenario: Game服务新增
- **WHEN** ZooKeeper收到Game节点新增事件
- **THEN** 检查Game是否在负责的分片
- **AND** 如果是，建立连接；否则忽略

#### Scenario: Game服务下线
- **WHEN** ZooKeeper收到Game节点删除事件
- **THEN** 断开与该Game的gRPC连接

### Requirement: 连接数限制

系统 SHALL 限制单个Gate连接的Game数量。

#### Scenario: 连接数达到上限
- **WHEN** Gate需要连接新Game
- **AND** 当前连接数已达到上限
- **THEN** 拒绝建立新连接

### Requirement: 渐进式重连

系统 SHALL 在滚动更新时采用渐进式重连策略。

#### Scenario: 批量重连
- **WHEN** Gate需要重连多个Game
- **THEN** 每次最多重连N个（可配置）
- **AND** 每次重连间隔T秒（可配置）

### Requirement: 分片策略

系统 SHALL 使用一致性哈希分片确定Gate负责的Game。

#### Scenario: 分片计算
- **WHEN** Gate启动或Game列表变化
- **THEN** 根据hash(gateId)计算负责的Game分片
- **AND** 只连接负责范围内的Game

### Requirement: 玩家路由

系统 SHALL 根据玩家ID使用一致性哈希将请求路由到对应的Game。

#### Scenario: 玩家登录路由
- **WHEN** 玩家发送登录消息到Gate
- **THEN** Gate计算 hash(playerId) % gameCount
- **AND** 根据计算结果路由到对应Game

#### Scenario: 会话保持
- **WHEN** 同一玩家再次发送消息
- **THEN** 使用相同哈希算法计算
- **AND** 确保路由到同一Game

### Requirement: 路由失败处理

系统 SHALL 在目标Game不可用时进行故障转移。

#### Scenario: Game故障转移
- **WHEN** 目标Game连接不可用
- **THEN** 尝试其他健康Game
- **AND** 返回错误或重试

### Requirement: 服务健康检测

系统 SHALL 通过心跳机制维持服务健康状态。

#### Scenario: Gate心跳
- **WHEN** Gate运行中
- **THEN** 每10秒更新ZooKeeper节点TTL

### Requirement: Watch事件处理

系统 SHALL 监听服务节点变化并及时响应。

#### Scenario: 监听Game节点变化
- **WHEN** Game服务上线/下线
- **THEN** Gate收到Watch事件通知
- **AND** 触发连接建立/断开（考虑分片）

### Requirement: 本地缓存

系统 SHALL 缓存Game服务列表以减少ZooKeeper压力。

#### Scenario: 使用缓存
- **WHEN** 需要获取Game列表
- **THEN** 先从本地缓存获取
- **AND** 定期刷新缓存

## 实现

- **ZooKeeper客户端**: 使用Curator Framework
- **服务注册**: `service/ServiceRegistry`
- **服务发现**: `service/ServiceDiscovery`
- **分片策略**: `service/ShardingStrategy`
- **路由策略**: `service/RoutingStrategy`
- **连接管理**: `grpc/GameGrpcClientPool` (修改)
- **本地缓存**: `service/ServiceCache`

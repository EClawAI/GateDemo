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
- **THEN** 建立与新Game的gRPC连接

#### Scenario: Game服务下线
- **WHEN** ZooKeeper收到Game节点删除事件
- **THEN** 断开与该Game的gRPC连接

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
- **AND** 触发连接建立/断开

## 实现

- **ZooKeeper客户端**: 使用Curator或ZooKeeper原生客户端
- **服务注册**: `service/ServiceRegistry`
- **服务发现**: `service/ServiceDiscovery`
- **连接管理**: `grpc/GameGrpcClientPool` (修改)

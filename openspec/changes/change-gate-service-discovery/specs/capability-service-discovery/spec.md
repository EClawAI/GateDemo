# 服务发现能力

## ADDED Requirements

### Requirement: Gate服务注册

系统 SHALL 在Gate启动时向ZooKeeper注册自身服务信息。

#### Scenario: Gate启动注册
- **WHEN** Gate服务启动
- **THEN** 在ZooKeeper创建临时节点 `/gate/{gateId}`
- **AND** 节点内容包含 host:port:healthy

### Requirement: EnterServer服务注册

系统 SHALL 在EnterServer启动时向ZooKeeper注册自身服务信息。

#### Scenario: EnterServer启动注册
- **WHEN** EnterServer服务启动
- **THEN** 在ZooKeeper创建临时节点 `/enter/{enterId}`
- **AND** 节点内容包含 host:port

### Requirement: 玩家登录

系统 SHALL 处理玩家登录请求并返回目标Game服务器地址。

#### Scenario: 首次登录
- **WHEN** 玩家首次登录
- **THEN** 查询Game服务器负载
- **AND** 选择负载最低的Game返回

#### Scenario: 返回登录
- **WHEN** 玩家已创建角色
- **THEN** 查询上次登录的Game
- **AND** 如果Game在线，返回原Game；否则选择其他Game

### Requirement: 会话保持

系统 SHALL 记录玩家的GameServer映射并保持会话。

#### Scenario: 玩家上线
- **WHEN** 玩家登录成功
- **THEN** 记录 playerId → gameId 映射到Redis

#### Scenario: 玩家下线
- **WHEN** 玩家断开连接
- **THEN** 更新Redis中的会话状态

### Requirement: 路由决策

系统 SHALL 根据玩家状态和Game负载做出路由决策。

#### Scenario: 负载均衡
- **WHEN** 需要为玩家选择Game
- **THEN** 查询各Game负载
- **AND** 选择负载最低的Game

### Requirement: 游戏服务发现

系统 SHALL 能够发现可用的Game服务列表。

#### Scenario: 发现Game列表
- **WHEN** EnterServer启动
- **THEN** 从ZooKeeper获取所有Game节点

### Requirement: 健康检测

系统 SHALL 通过心跳机制检测服务健康状态。

## 实现

- **EnterServer**: `enter-service` 模块
- **LoginHandler**: `enter/handler/LoginHandler`
- **GameRouter**: `enter/router/GameRouter`
- **SessionManager**: `enter/session/SessionManager`
- **ServiceDiscovery**: `common/ServiceDiscovery`

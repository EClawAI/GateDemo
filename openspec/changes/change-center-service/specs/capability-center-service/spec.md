# CenterService能力

## ADDED Requirements

### Requirement: 版本检查

系统 SHALL 提供版本检查接口，返回当前服务端支持的客户端版本信息。

#### Scenario: 客户端请求版本
- **WHEN** 客户端请求版本检查接口
- **THEN** 返回当前版本号、最小版本号、是否强制更新等信息

### Requirement: Gate列表查询

系统 SHALL 提供Gate服务器列表接口。

#### Scenario: 获取Gate列表
- **WHEN** 客户端请求Gate列表
- **THEN** 返回所有在线Gate的地址和端口

### Requirement: 游戏服路由

系统 SHALL 提供游戏服路由接口，返回玩家应该连接的gameId。

#### Scenario: 新角色登录
- **WHEN** 玩家首次登录游戏
- **THEN** 返回推荐服的gameId

#### Scenario: 已登录玩家
- **WHEN** 玩家已创建角色
- **THEN** 返回上次登录的gameId

#### Scenario: 上次Game已下线
- **WHEN** 上次登录的Game服已下线
- **THEN** 返回推荐服的gameId

### Requirement: 登录记录

系统 SHALL 记录玩家的登录信息。

#### Scenario: 玩家登录Game
- **WHEN** 玩家成功登录Game服
- **THEN** 记录playerId和gameId到Redis

### Requirement: Gate心跳

系统 SHALL 接收Gate的心跳上报。

#### Scenario: Gate上报状态
- **WHEN** Gate服务定期上报心跳
- **THEN** 更新Gate在线人数

## 实现

- **CenterService**: `center-service` 模块
- **VersionController**: 版本接口
- **GateController**: Gate接口
- **GameRouteController**: 路由接口
- **Redis**: 会话存储

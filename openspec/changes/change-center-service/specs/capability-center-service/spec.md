# CenterService能力

## ADDED Requirements

### Requirement: 入口接口

系统 SHALL 提供一次请求获取所有入口信息。

#### Scenario: 客户端请求入口
- **WHEN** 客户端请求入口接口
- **THEN** 返回版本信息、Gate信息和gameId

### Requirement: 版本检查

系统 SHALL 在入口接口中返回版本信息。

#### Scenario: 版本检查
- **WHEN** 客户端请求入口接口
- **THEN** 返回当前版本号、最小版本号、是否强制更新

### Requirement: Gate信息

系统 SHALL 在入口接口中返回Gate服务器信息。

#### Scenario: Gate信息返回
- **WHEN** 客户端请求入口接口
- **THEN** 返回可用Gate的地址和端口

### Requirement: 游戏服路由

系统 SHALL 在入口接口中返回gameId。

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
- **AND** 更新Gate在线人数

## 实现

- **CenterService**: `center-service` 模块
- **EnterController**: 入口接口
- **Redis**: 会话存储

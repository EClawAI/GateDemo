# capability-login-service Specification

## Purpose
TBD - created by archiving change change-login-service. Update Purpose after archive.
## Requirements
### Requirement: 登录接口

系统 SHALL 提供登录接口，返回Gate地址和gameId。

#### Scenario: 玩家登录
- **WHEN** 玩家请求登录接口
- **THEN** 返回Gate地址和gameId

### Requirement: Gate分配

系统 SHALL 返回可用的Gate服务器地址。

#### Scenario: Gate分配
- **WHEN** 玩家请求登录
- **THEN** 返回可用Gate的地址和端口

### Requirement: 游戏服路由

系统 SHALL 返回gameId。

#### Scenario: 新角色登录
- **WHEN** 玩家首次登录游戏
- **AND** 无登录记录
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
- **AND** 通知LoginService
- **THEN** 记录playerId和gameId到Redis

### Requirement: Gate心跳

系统 SHALL 接收Gate的心跳上报。

#### Scenario: Gate上报状态
- **WHEN** Gate服务定期上报心跳
- **AND** 更新Gate在线人数


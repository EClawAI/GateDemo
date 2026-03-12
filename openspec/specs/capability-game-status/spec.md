# capability-game-status Specification

## Purpose
TBD - created by archiving change change-game-status-management. Update Purpose after archive.
## Requirements
### Requirement: Game状态定义

系统 SHALL 定义Game服务器状态。

#### Scenario: 状态定义
- **WHEN** 系统需要定义Game状态
- **THEN** 定义三种状态：未启动、不可登录、可登录

### Requirement: Game状态存储

系统 SHALL 将Game状态存储到Redis。

#### Scenario: Game启动
- **WHEN** Game服务启动
- **THEN** 在Redis中设置状态为"已启动但不可登录"

#### Scenario: Game初始化完成
- **WHEN** Game服务初始化完成
- **THEN** 在Redis中设置状态为"可以登录"

#### Scenario: Game关闭
- **WHEN** Game服务关闭
- **THEN** 在Redis中设置状态为"未启动"

### Requirement: Game状态心跳

系统 SHALL 定期同步Game状态到Redis。

#### Scenario: 状态同步
- **WHEN** Game服务运行中
- **THEN** 每30秒同步状态到Redis

### Requirement: Login状态过滤

系统 SHALL 根据Game状态过滤不可用的服务器。

#### Scenario: 上次服可用
- **WHEN** 玩家上次登录的Game状态为可登录
- **THEN** 返回该Game

#### Scenario: 上次服不可用，推荐服可用
- **WHEN** 玩家上次登录的Game状态为不可登录
- **AND** 推荐服可登录
- **THEN** 返回推荐服+重定向提示

#### Scenario: 上次服和推荐服都不可用
- **WHEN** 上次登录的Game不可登录
- **AND** 推荐服也不可登录
- **THEN** 返回错误"服务器不可用"

### Requirement: 客户端引导

系统 SHALL 在返回推荐服时提供提示信息。

#### Scenario: 返回重定向信息
- **WHEN** 玩家被重定向到推荐服
- **THEN** 返回redirect=true和redirectMessage


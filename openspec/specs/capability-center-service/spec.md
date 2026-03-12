# capability-center-service Specification

## Purpose
TBD - created by archiving change change-center-service. Update Purpose after archive.
## Requirements
### Requirement: 配置接口

系统 SHALL 提供配置接口，返回客户端需要的配置信息。

#### Scenario: 客户端获取配置
- **WHEN** 客户端请求配置接口
- **THEN** 返回版本、SDK地址、Login地址、公告等信息

### Requirement: 版本检查

系统 SHALL 返回客户端版本信息。

#### Scenario: 版本信息
- **WHEN** 客户端请求配置接口
- **THEN** 返回版本号、最小版本号、是否强制更新、更新地址

### Requirement: SDK地址

系统 SHALL 返回SDK服务器地址。

#### Scenario: SDK地址
- **WHEN** 客户端请求配置接口
- **THEN** 返回SDK服务器的host和port

### Requirement: Login地址

系统 SHALL 返回Login服务地址。

#### Scenario: Login地址
- **WHEN** 客户端请求配置接口
- **THEN** 返回Login服务的host和port

### Requirement: 公告信息

系统 SHALL 返回游戏公告信息。

#### Scenario: 公告信息
- **WHEN** 客户端请求配置接口
- **THEN** 返回公告标题、内容、类型


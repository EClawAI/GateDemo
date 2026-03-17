## Why

当前存在以下问题：
- 各服务使用本地配置文件 + 环境变量，配置变更需重新部署
- 所有配置仅启动时加载，不可动态变更
- Game 使用 Redis 手工注册，Gate→Login/Center 地址硬编码，缺乏统一服务发现

## What Changes

引入外部配置中心与服务注册发现：
- 引入 Nacos 作为配置中心和服务注册发现一体化方案
- 通过 Nacos SDK 监听配置变更，实现运行时热更新
- 统一服务注册与发现，替代硬编码地址

## 核心功能

1. **外部配置中心（Nacos）**
   - 通过 Nacos SDK 拉取和监听配置
   - 多环境配置隔离（dev/staging/prod）
   - 敏感信息与业务配置分离

2. **配置热更新**
   - Nacos SDK 监听配置变更回调
   - 动态更新限流阈值、日志级别、心跳间隔等运行时参数
   - 无需重启即可生效

3. **统一服务注册与发现**
   - Game 接入 Nacos 注册中心（替代 Redis 手工注册）
   - Gate 通过 Nacos 服务发现获取 Login/Center 地址
   - 去除硬编码 IP/端口

## Impact

- 影响全部服务（Nacos SDK 集成、配置迁移）
- 影响 gate-service（服务发现）
- 影响 game-service（服务注册）
- 影响 login-service、center-service（服务注册与配置）

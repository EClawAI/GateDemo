## Why

当前 Redis 使用存在以下问题：
- Redis 为单节点，宕机导致服务发现、离线消息、Game 状态全部不可用
- 部分 Redis key 无明确 TTL，长期运行存在 OOM 风险

## What Changes

提升 Redis 高可用与键生命周期管理：
- RedisConfig 增加 Sentinel 配置支持
- 为所有 Redis key 规划 TTL（离线消息 7 天 / 心跳 2 分钟 / Token 过期同步）
- 配置 maxmemory 与 eviction policy

## 核心功能

1. **Redis Sentinel 配置**
   - 支持 Redis Sentinel 高可用模式
   - 故障自动切换

2. **Redis Key TTL 规划**
   - 离线消息 key：7 天 TTL
   - 心跳 key：2 分钟 TTL
   - Token 相关 key：与业务过期时间同步

3. **maxmemory 策略**
   - 配置 maxmemory 上限
   - 配置 eviction policy 淘汰策略

## Impact

- 影响 `gate-service` RedisConfig、key TTL 设置
- 影响 `game-service` RedisConfig、key TTL 设置
- 影响 `login-service` RedisConfig、key TTL 设置

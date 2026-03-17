## Why

当前存在以下风险：
- AES 密钥硬编码，无轮换机制，泄露后无法快速失效
- 消息处理无流控，下游慢时上游可能 OOM
- Redis 单节点无备份策略，数据丢失风险高

## What Changes

建立密钥管理、背压与备份机制：
- 密钥存储外部化（Vault/KMS）+ 密钥版本轮换
- gRPC Stream 使用 onReady 回调控制发送速率，Redis Stream 监控 pending 消息
- 开启 Redis RDB + AOF 持久化，制定数据库备份策略

## 核心功能

1. **密钥管理与轮换**
   - 密钥存储外部化（Vault/KMS）
   - 支持多版本密钥
   - 密钥轮换流程与过期处理

2. **gRPC/Redis Stream 背压**
   - gRPC Stream 使用 onReady 回调控制发送速率
   - Redis Stream 监控 pending 消息堆积
   - 下游慢时上游限流或排队

3. **Redis 数据备份（RDB/AOF）**
   - 开启 RDB 快照
   - 开启 AOF 持久化
   - 备份恢复演练

4. **数据库备份策略**
   - 若引入持久化数据库，制定备份计划
   - 定时备份与异地存储
   - 恢复 SOP 文档

## Impact

- 影响 gate-service（密钥集成、背压逻辑）
- 影响 game-service（密钥集成、背压）
- 影响运维配置（Vault、Redis 持久化、备份脚本）

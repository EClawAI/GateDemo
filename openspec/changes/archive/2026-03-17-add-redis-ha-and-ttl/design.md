# Redis 高可用与 TTL 规划 (design.md)

## Context

- **当前状态**：Redis 以单节点模式使用，服务发现、离线消息、Game 状态等均依赖 Redis；部分 Redis key 无明确 TTL，长期运行存在 OOM 风险。
- **问题**：Redis 宕机导致服务发现、离线消息、Game 状态全部不可用；无 TTL 的 key 持续增长，可能导致 Redis 内存爆满；未配置 maxmemory 与 eviction policy，淘汰行为不可控。
- **约束**：使用 Lettuce 客户端；保持与现有 Redis 使用方式的兼容；不引入 Spring Data Redis 等 Spring 专属能力。

## Goals / Non-Goals

**Goals:**
- RedisConfig 增加 Redis Sentinel 配置支持（Lettuce 原生支持）
- 为所有 Redis key 规划 TTL（离线消息 7 天、心跳 2 分钟、Token 与 session 过期同步）
- 配置 Redis maxmemory + eviction policy

**Non-Goals:**
- 不实现 Redis Cluster 分片（本 change 仅 Sentinel 高可用）
- 不改变业务 key 的命名或数据结构
- 不引入 Redis 以外的缓存方案

## Decisions

1. **Redis Sentinel 配置**
   - 在 RedisConfig 中增加 `redis.sentinel.master`、`redis.sentinel.nodes` 等配置；当配置了 Sentinel 时，使用 Lettuce 的 `RedisSentinelClient` 或等效 API 连接；未配置时继续使用单节点模式。
   - **理由**：Lettuce 原生支持 Sentinel，无需额外依赖；生产环境可自动故障切换，提升可用性。

2. **Key TTL 规划**
   - 离线消息 key：TTL 7 天（604800 秒）
   - 心跳/实例注册 key：TTL 2 分钟（120 秒），由心跳逻辑周期刷新
   - Token、session 相关 key：与业务过期时间同步（如 token 过期 24h，则 TTL 设为 24h）
   - **理由**：离线消息保留一定时长供用户补拉；心跳短 TTL 便于实例下线时快速剔除；Token 与业务过期一致，避免过早淘汰或长期滞留。

3. **maxmemory 与 eviction policy**
   - 在 Redis 服务端配置 maxmemory（如物理内存的 75% 或固定值）；配置 eviction policy 为 `volatile-ttl` 或 `allkeys-lru`，优先淘汰有过期时间的 key 或按 LRU 淘汰。
   - **理由**：防止 Redis 无限增长；volatile-ttl 与业务 TTL 规划配合良好；在文档与运维手册中明确推荐配置。

4. **单机与 Sentinel 切换**
   - 通过配置开关或是否有 Sentinel 配置判断：有 Sentinel 配置则启用 Sentinel 模式，否则使用单节点。
   - **理由**：开发环境可继续用单机，生产用 Sentinel，便于多环境部署。

## Risks / Trade-offs

- **[风险]** Sentinel 部署复杂度增加 → 在部署文档中说明 Sentinel 最小拓扑（1 master + 2 replica + 3 sentinel）；提供 docker-compose 示例便于本地验证。
- **[风险]** TTL 与业务逻辑冲突 → 确保心跳刷新周期小于 TTL（如心跳 30s、TTL 2min），避免误剔除；Token key 的 TTL 与业务过期时间严格同步。
- **[权衡]** eviction 可能淘汰仍在用的 key → 使用 volatile-ttl 时仅淘汰带 TTL 的 key，核心无 TTL key 需谨慎；建议所有业务 key 均设 TTL 或接受 LRU 淘汰。

## Migration Plan

- **实现顺序**：先为现有 Redis key 补充 TTL 设置 → 配置 maxmemory 与 eviction policy（需运维在 Redis 服务端执行）→ 最后增加 Sentinel 配置支持与接入。
- **部署**：TTL 与 maxmemory 无数据迁移；Sentinel 模式需先部署 Sentinel 集群，再修改应用配置并重启。
- **回滚**：未配置 Sentinel 时保持单节点；若 Sentinel 有问题，将配置改回单节点 URL 即可回退。

## Open Questions

- 不同服务（gate、game、login）的 Redis 是否共用同一实例？若共用，maxmemory 需按总量规划；若独立，可分别配置。
- 离线消息 7 天是否满足业务？可根据产品需求调整。

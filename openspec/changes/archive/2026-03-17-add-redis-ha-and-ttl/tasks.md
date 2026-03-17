## 1. Redis Sentinel 配置

- [x] 1.1 在 RedisConfig 中增加 redis.sentinel.master、redis.sentinel.nodes 等配置项
- [x] 1.2 当配置了 Sentinel 时，使用 Lettuce RedisSentinelClient 或等效 API 创建连接
- [x] 1.3 未配置 Sentinel 时保持现有单节点连接逻辑
- [ ] 1.4 在 gate-service、game-service、login-service 中统一使用该配置

## 2. 离线消息 Key TTL

- [ ] 2.1 定位所有离线消息相关 Redis 写入逻辑
- [ ] 2.2 在写入时设置 TTL 为 7 天（604800 秒）
- [ ] 2.3 验证读取逻辑在 TTL 内正常工作

## 3. 心跳/实例注册 Key TTL

- [ ] 3.1 定位心跳、实例注册等 Redis key 的写入与刷新逻辑
- [ ] 3.2 设置 TTL 为 2 分钟，心跳周期刷新 TTL（如每 30 秒）
- [ ] 3.3 确认 GateClusterManager、Game 注册等逻辑已按此实现

## 4. Token 与 Session Key TTL

- [ ] 4.1 定位 login-service 中 Token、session 的 Redis 写入逻辑
- [ ] 4.2 将 TTL 与业务过期时间同步（如 Token 24h、session 7 天等）
- [ ] 4.3 在续期或刷新时同步更新 TTL

## 5. maxmemory 与 eviction 文档

- [x] 5.1 在运维/部署文档中说明 Redis maxmemory 推荐配置（如物理内存 75%）
- [x] 5.2 说明 eviction policy 推荐值（volatile-ttl 或 allkeys-lru）及选择依据
- [x] 5.3 提供 redis.conf 或 docker-compose 中的示例配置

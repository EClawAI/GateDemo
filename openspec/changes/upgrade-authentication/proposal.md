## Why

当前认证机制存在以下问题：
- TokenService 使用内存 ConcurrentHashMap 存储 token，多实例不共享，重启失效
- Gate 多实例部署时，某实例签发的 token 在其他实例无法验证
- GateNettyWebSocketHandler 认证仅检查 player_id 字段，无 token 验证，存在安全漏洞

## What Changes

升级为 JWT + Redis 的分布式认证方案：
- TokenService 升级为 JWT 生成与验签
- Redis 存储 token 黑名单及会话信息
- 在 WebSocket 认证流程中集成 TokenValidator，强制校验 token

## 核心功能

1. **JWT Token 生成与验签**
   - 登录成功后签发 JWT
   - Gate 收到 WebSocket 连接时验签并解析 claims

2. **Redis Token 存储**
   - 存储 token 黑名单（登出/失效）
   - 可选存储活跃会话，支持多实例共享

3. **WebSocket 入口 Token 验证**
   - 在 GateNettyWebSocketHandler 握手阶段校验 token
   - token 无效或缺失时拒绝连接

## Impact

- 影响 `gate-service` TokenService、WebSocket 认证流程
- 影响 `login-service` 签发 JWT
- 新增 Redis 依赖，需 docker-compose 等部署配置配合

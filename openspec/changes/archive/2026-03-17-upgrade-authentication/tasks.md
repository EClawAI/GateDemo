# upgrade-authentication 任务清单

## 1. JWT 工具类与 TokenService 升级

- [x] 1.1 引入 JWT 库（jjwt 或 nimbus-jose-jwt），实现 JwtGenerator、JwtValidator 工具类
- [x] 1.2 实现 generateToken(playerId, expiration) 与 validateToken(token) 方法
- [x] 1.3 在 login-service 中，登录成功后调用 JwtGenerator 签发 JWT 并返回
- [x] 1.4 配置 JWT 密钥（对称或非对称），从环境变量或配置文件注入

## 2. Redis token 黑名单

- [x] 2.1 定义黑名单 Redis key 格式（如 token:blacklist:{jti}）及 TTL 策略
- [x] 2.2 实现 TokenBlacklistService：addToBlacklist(token/jti, ttlSeconds)、isBlacklisted(token/jti)
- [x] 2.3 在登出或 token 失效流程中调用 addToBlacklist
- [x] 2.4 在 TokenValidator 验签前先调用 isBlacklisted，黑名单则直接拒绝

## 3. WebSocket 集成 TokenValidator

- [x] 3.1 在 GateNettyWebSocketHandler 中集成 TokenValidator，从 URL query 或首帧解析 token
- [x] 3.2 握手或首帧处理时调用 validateToken，验签并查黑名单
- [x] 3.3 认证通过后将 player_id 存入 Channel.attr() 或等效上下文
- [x] 3.4 token 无效时关闭连接并记录日志
- [x] 3.5 移除或替换原有的仅检查 player_id 逻辑

## 4. 未认证连接消息限制

- [x] 4.1 在消息处理入口判断 Channel 是否已认证
- [x] 4.2 未认证时仅允许 type=auth（或等效）的认证消息
- [x] 4.3 未认证收到业务消息时拒绝并关闭连接或返回错误
- [x] 4.4 认证消息处理成功后标记 Channel 为已认证

## 5. 配置与部署

- [x] 5.1 在 gate、login 配置中添加 jwt.secret 或 jwt.key-path 等配置项
- [x] 5.2 确保 docker-compose 等部署中 Redis 可用，各服务可连接
- [x] 5.3 更新 API 文档或 README，说明客户端需在连接时携带 JWT（query 或首帧）

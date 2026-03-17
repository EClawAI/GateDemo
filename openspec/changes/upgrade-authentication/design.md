## Context

- **当前状态**：TokenService 使用内存 ConcurrentHashMap 存储 token，多 Gate 实例不共享，某实例签发的 token 在其他实例无法验证；GateNettyWebSocketHandler 在 WebSocket 认证流程中仅检查 player_id 字段，无 token 验证，存在安全漏洞；重启后 token 全部失效。
- **问题**：多实例部署时认证不一致；无 token 验签导致可伪造身份；token 无法跨实例验证与失效（如登出）。
- **约束**：不使用 Spring Security 或 Spring 认证组件；在现有 Netty WebSocket 与 HTTP 框架内集成；需与 login-service 协作签发 JWT，Gate 验签。

## Goals / Non-Goals

**Goals:**
- TokenService 升级为 JWT 生成与验签
- Redis 存储 token 黑名单及会话信息，支持登出与多实例共享
- 在 WebSocket 握手阶段集成 TokenValidator，强制校验 token
- 未认证连接仅允许发送认证消息，认证通过后才允许其他业务消息

**Non-Goals:**
- 不实现 OAuth2、OIDC 等第三方认证协议
- 不在此 change 中实现刷新 token 机制（可后续扩展）
- 不改变现有 login HTTP 接口的请求/响应格式（仅将 token 内容改为 JWT）

## Decisions

1. **JWT 库与格式**
   - 使用 jjwt（io.jsonwebtoken）或 nimbus-jose-jwt 进行 JWT 生成与验签
   - Payload 包含：sub（player_id）、exp、iat；可选 iss、aud
   - 使用 HMAC-SHA256 或 RS256；开发环境可用对称密钥，生产建议非对称
   - **理由**：JWT 标准、无状态验签，Gate 无需查询 login-service 即可验证；密钥需安全配置。

2. **Redis 存储**
   - 黑名单：登出或 token 失效时，将 token ID（jti）或 token 本身写入 Redis 黑名单，TTL 与 token 过期时间一致
   - 可选：活跃会话存储 player_id -> token_id 等，用于多端登录控制
   - **理由**：支持登出即失效；多 Gate 实例共享黑名单，验签前先查黑名单。

3. **WebSocket 认证流程**
   - 连接建立时：从 URL query 或首帧消息中解析 token
   - TokenValidator：先查 Redis 黑名单，再 JWT 验签与解析 claims
   - 未认证：仅允许发送认证消息（如 auth 类型），其他消息拒绝并关闭连接
   - 认证通过：将 player_id 等绑定到 Channel，后续消息均可处理
   - **理由**：最小权限；防止未认证连接发送任意业务消息。

4. **login-service 签发 JWT**
   - 登录成功后，由 login-service 生成 JWT 并返回给客户端
   - Gate 与 login 共享密钥（或公钥/私钥对）；通过配置或环境变量注入
   - **理由**：职责分离；login 负责签发，Gate 负责验签。

## Risks / Trade-offs

- **[风险]** 密钥泄露导致 token 可伪造 → 密钥从环境变量或 Secret 管理注入，不入库；生产使用强密钥与定期轮换。
- **[权衡]** 每次消息都查 Redis 黑名单增加延迟 → 仅在握手时查一次；或对近期登出 token 做短时本地缓存。
- **[风险]** 未认证连接被滥用发认证消息 → 限制认证消息频率（如每连接最多尝试 N 次）；超限则关闭连接。

## Migration Plan

- **实现顺序**：先实现 JWT 生成与验签工具类 → login-service 签发 JWT → Redis 黑名单读写 → Gate TokenValidator 集成 → WebSocket 握手强制校验 → 未认证连接限制。
- **部署**：需提前配置 JWT 密钥、Redis 连接；login 与 Gate 同时部署新版本，否则旧 token 格式可能不兼容。
- **回滚**：可保留旧 TokenService 实现，通过配置开关回退到"仅检查 player_id"模式；需评估安全风险。

## Open Questions

- token 传递方式：URL query（ws://host/path?token=xxx）还是首帧 JSON？建议支持 query，便于现有客户端兼容。
- 黑名单 key 设计：使用 jti 还是 token 全文？jti 更省空间，需确保 JWT 中包含 jti。

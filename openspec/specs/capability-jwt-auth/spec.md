# capability-jwt-auth Specification

## Purpose
TBD - created by archiving change upgrade-authentication. Update Purpose after archive.
## Requirements
### Requirement: TokenService 支持 JWT 生成与验签

系统 SHALL 提供 JWT 生成与验签能力；MUST 使用标准 JWT 格式，包含 sub（player_id）、exp、iat 等 claims；SHALL 支持通过配置注入签名密钥；验签失败时 MUST 返回明确错误。

#### Scenario: 登录成功后签发 JWT
- **WHEN** login-service 完成用户验证并需签发 token
- **THEN** TokenService 生成 JWT，包含 player_id、过期时间等
- **AND** 返回给客户端的 token 可被 Gate 验签并解析出 player_id

#### Scenario: Gate 验签 token
- **WHEN** Gate 收到 WebSocket 连接携带的 token
- **THEN** TokenValidator 使用共享密钥验签
- **AND** 验签成功则解析 claims 获取 player_id；失败则拒绝连接

### Requirement: Redis 存储 token 黑名单

系统 SHALL 在 Redis 中维护 token 黑名单；SHALL 在用户登出或 token 主动失效时将 token 加入黑名单；验签前 MUST 先查询黑名单，黑名单中的 token 即使签名有效也须拒绝；SHALL 为黑名单项设置 TTL，与 token 过期时间一致。

#### Scenario: 登出加入黑名单
- **WHEN** 用户登出或主动使 token 失效
- **THEN** 系统将 token（或 jti）写入 Redis 黑名单
- **AND** 设置 TTL 至 token 原有过期时间

#### Scenario: 黑名单 token 被拒绝
- **WHEN** Gate 收到已加入黑名单的 token
- **THEN** 即使 JWT 验签通过，也拒绝连接
- **AND** 返回认证失败或连接关闭

### Requirement: WebSocket 握手阶段强制 token 校验

系统 SHALL 在 GateNettyWebSocketHandler 握手或首帧处理时集成 TokenValidator；MUST 从 URL query 或首帧消息中解析 token；SHALL 在 token 无效或缺失时拒绝连接；MUST 在认证通过后将 player_id 绑定到 Channel 属性，供后续消息使用。

#### Scenario: 有效 token 通过认证
- **WHEN** 客户端携带有效 JWT 建立 WebSocket 连接
- **THEN** TokenValidator 验签成功且未在黑名单中
- **AND** 连接被标记为已认证，可接收业务消息

#### Scenario: 无效 token 拒绝连接
- **WHEN** 客户端携带无效、过期或黑名单中的 token
- **THEN** Gate 拒绝连接并关闭 Channel
- **AND** 可返回错误码或消息说明认证失败

### Requirement: 未认证连接仅允许认证消息

系统 SHALL 对未认证连接的入站消息进行限制；MUST 仅允许发送认证相关消息（如 type=auth 且携带 token）；SHALL 拒绝其他业务消息；拒绝时 MAY 关闭连接或返回错误。

#### Scenario: 未认证发送认证消息
- **WHEN** 未认证连接发送仅包含认证信息的消息
- **THEN** 系统尝试解析并校验 token
- **AND** 校验通过则转为已认证，后续可处理业务消息

#### Scenario: 未认证发送业务消息
- **WHEN** 未认证连接发送非认证类型的业务消息
- **THEN** 系统拒绝处理该消息
- **AND** 可关闭连接或返回错误提示需先认证


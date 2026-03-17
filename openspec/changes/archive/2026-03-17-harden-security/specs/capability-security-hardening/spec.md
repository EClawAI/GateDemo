# capability-security-hardening (Delta)

## Purpose

加强消息加密、REST 鉴权与输入校验，提升 Gate 及内部服务的安全性。

## ADDED Requirements

### Requirement: MessageEncryptor 使用 AES-GCM

系统 SHALL 将 MessageEncryptor 的加密算法从 AES/ECB 升级为 AES-GCM；SHALL 使用随机 IV（12 字节）与认证标签；MUST 在加密时生成 IV 并随密文传递，解密时提取 IV 与 tag 进行验证。

#### Scenario: GCM 加密
- **WHEN** 对消息体调用 MessageEncryptor.encrypt
- **THEN** 使用 AES-GCM 加密
- **AND** 输出包含 IV、密文与认证 tag

#### Scenario: GCM 解密
- **WHEN** 对密文调用 MessageEncryptor.decrypt
- **THEN** 验证认证 tag 并解密
- **AND** 若 tag 验证失败则拒绝解密并返回错误

### Requirement: 编解码 Pipeline 集成 MessageEncryptor

系统 SHALL 在 GameMessageEncoder 与 GameMessageDecoder 中根据消息头 flags 的加密标志位调用 MessageEncryptor；若标志位表明消息体已加密，则 MUST 在编码前加密、解码后解密；SHALL 支持通过配置开关控制是否启用加密。

#### Scenario: 编码时加密
- **WHEN** 消息头 flags 标明加密且加密功能已启用
- **THEN** Encoder 在写入 body 前调用 MessageEncryptor.encrypt
- **AND** 密文写入输出流

#### Scenario: 解码时解密
- **WHEN** 消息头 flags 标明加密且加密功能已启用
- **THEN** Decoder 在解析 body 前调用 MessageEncryptor.decrypt
- **AND** 解密后的明文参与后续解析

#### Scenario: 加密未启用
- **WHEN** 配置关闭加密或 flags 未标明加密
- **THEN** 编解码不调用 MessageEncryptor
- **AND** 消息体以明文处理

### Requirement: REST API Key 认证

系统 SHALL 为 login-service 与 center-service 的内部 REST 接口添加 API Key 认证；请求 MUST 在 Header 中携带合法 API Key（如 X-API-Key），否则返回 401；SHALL 支持通过配置指定合法 Key 列表或单个 Key。

#### Scenario: 携带合法 Key 时通过
- **WHEN** 请求 Header 包含合法 API Key
- **THEN** 请求正常进入业务处理
- **AND** 返回正常响应

#### Scenario: 未携带或 Key 错误时拒绝
- **WHEN** 请求未携带 API Key 或 Key 与配置不匹配
- **THEN** 返回 401 Unauthorized
- **AND** 不执行业务逻辑

#### Scenario: 配置可禁用校验
- **WHEN** 配置允许跳过 API Key 校验（如开发环境）
- **THEN** 不校验 Key 即可通过
- **AND** 生产环境应强制启用

### Requirement: 消息体最大长度限制

系统 SHALL 对 WebSocket 文本消息与二进制消息体施加最大长度限制；超过限制时 MUST 拒绝处理并关闭连接或返回错误；限制值 SHALL 可配置。

#### Scenario: WebSocket 消息长度限制
- **WHEN** WebSocket 收到的文本消息超过配置的最大长度（如 64KB）
- **THEN** 拒绝该消息并关闭连接或返回错误
- **AND** 记录日志

#### Scenario: 二进制消息体长度限制
- **WHEN** 解码时发现 bodyLength 超过配置的最大值
- **THEN** 拒绝该消息并关闭连接
- **AND** 不分配超额内存

### Requirement: 消息字段校验

系统 SHALL 对解析后的消息对象（如 PlayerMessage、LoginRequest）进行字段校验；SHALL 使用 Jakarta Bean Validation 或自定义校验逻辑；校验失败时 MUST 返回明确错误并拒绝继续处理。

#### Scenario: 必填字段缺失
- **WHEN** 消息缺少必填字段（如 player_id 为 null）
- **THEN** 校验失败并返回错误
- **AND** 不进入业务逻辑

#### Scenario: 字段格式或范围非法
- **WHEN** 字段值超出合法范围（如 player_id 为负数、字符串超长）
- **THEN** 校验失败并返回错误
- **AND** 记录校验失败原因

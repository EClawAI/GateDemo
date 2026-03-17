## Why

当前存在以下安全问题：
- MessageEncryptor 使用 AES/ECB/PKCS5Padding 已存在但未被调用
- login-service、center-service 的 REST 接口无认证鉴权
- 消息体解析无长度限制和校验，存在注入与溢出风险

## What Changes

加强安全防护能力：
- 在编解码 Pipeline 集成 MessageEncryptor，将 ECB 升级为 GCM 模式
- 内部 REST API 添加 API Key 认证
- 增加消息体最大长度限制和字段 validation

## 核心功能

1. **MessageEncryptor 集成 (AES-GCM)**
   - 在编解码 Pipeline 中调用 MessageEncryptor
   - 升级 AES/ECB 为 AES-GCM 模式

2. **REST API Key 鉴权**
   - 内部 REST 接口增加 API Key 校验
   - 未携带或错误的 Key 拒绝访问

3. **消息体输入校验**
   - 限制消息体最大长度
   - 增加字段 validation，防止畸形数据

## Impact

- 影响 `gate-service` 编解码 Pipeline 集成加密
- 影响 `player-client` 配合加密与校验
- 影响 `login-service` REST 鉴权、消息校验
- 影响 `center-service` REST 鉴权、消息校验

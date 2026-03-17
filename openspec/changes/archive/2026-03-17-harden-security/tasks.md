## 1. MessageEncryptor 升级为 AES-GCM

- [ ] 1.1 将 MessageEncryptor 的 TRANSFORMATION 改为 AES/GCM/NoPadding
- [ ] 1.2 实现 IV 生成（12 字节随机）及密文格式 IV||ciphertext||tag
- [ ] 1.3 实现 decrypt 时提取 IV、验证 tag 并解密
- [ ] 1.4 保持 encrypt/decrypt 接口兼容，必要时增加重载以支持新格式
- [ ] 1.5 添加单元测试覆盖 GCM 加解密及 tag 验证失败场景

## 2. 编解码 Pipeline 集成加密

- [ ] 2.1 在 GameMessageEncoder 中根据 flags 加密标志位，在写入 body 前调用 MessageEncryptor.encrypt
- [ ] 2.2 在 GameMessageDecoder 中根据 flags 加密标志位，在解析 body 前调用 MessageEncryptor.decrypt
- [x] 2.3 添加 gate.security.encryption.enabled 配置项，未启用时不调用加解密
- [ ] 2.4 将 MessageEncryptor 通过构造函数或 DI 注入 Encoder/Decoder
- [ ] 2.5 验证加密开启时 Gate 与 player-client 可正确编解码（需 player-client 同步支持）

## 3. REST API Key 认证

- [x] 3.1 在 login-service 的 HTTP 处理链中添加 API Key 校验 Filter 或 Interceptor
- [ ] 3.2 在 center-service 的 HTTP 处理链中添加 API Key 校验 Filter 或 Interceptor
- [x] 3.3 从 Header（X-API-Key 或 Authorization）提取 Key，与配置比对
- [x] 3.4 未携带或错误时返回 401，并记录日志
- [x] 3.5 添加配置项（如 api.key、api.key.enabled）支持 Key 配置与开关
- [ ] 3.6 更新 Gate 调用 login/center 时携带 API Key

## 4. 消息体长度限制与字段校验

- [ ] 4.1 在 GateNettyWebSocketHandler 或前置 Handler 中检查 frame.text() 长度，超限则关闭连接或返回错误
- [x] 4.2 添加 gate.message.maxLength 等配置项（默认如 64KB）
- [x] 4.3 为 PlayerMessage、LoginRequest 等 DTO 添加 Bean Validation 注解（@NotNull、@Size、@Min 等）或自定义校验
- [x] 4.4 在解析后、业务处理前执行校验，失败时返回明确错误码

## 5. 文档与测试

- [ ] 5.1 在配置文档中说明加密开关、API Key、长度限制等参数
- [ ] 5.2 补充 MessageEncryptor GCM 的单元测试
- [ ] 5.3 补充 API Key Filter 的单元测试（合法/非法 Key、未携带）
- [ ] 5.4 说明 player-client 与 Gate 的加密兼容策略及升级顺序

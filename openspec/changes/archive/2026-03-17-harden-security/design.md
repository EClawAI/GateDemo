# 安全加固 (harden-security)

## Context

- **当前状态**：MessageEncryptor 使用 AES/ECB/PKCS5Padding 实现，类已存在但未被编解码 Pipeline 调用；login-service、center-service 的 REST 接口无认证鉴权，任何可访问网络的客户端均可调用；消息体解析存在长度检查（如 GameMessageDecoder 的 10MB 限制）但 WebSocket JSON 路径无显式长度与字段校验，存在注入与畸形数据风险。
- **问题**：ECB 模式不安全（相同明文产生相同密文）；内部 API 无保护易被滥用；缺乏系统性的消息体校验，易受畸形数据或超大负载攻击。
- **约束**：需与 player-client 配合（加密需两端一致）；REST API Key 为内部服务间调用设计，不面向公网用户；不改变现有业务接口的请求/响应结构，仅增加校验与鉴权层。

## Goals / Non-Goals

**Goals:**

- 在编解码 Pipeline 集成 MessageEncryptor，将 AES/ECB 升级为 AES-GCM 模式；根据消息头加密标志位在 Encoder/Decoder 中调用加解密。
- 为 login-service、center-service 的内部 REST API 添加 API Key 认证；请求需携带合法 Key 方可访问。
- 增加消息体最大长度限制（对 WebSocket JSON 等路径）及字段 validation（使用 Jakarta Bean Validation 或自定义校验），防止畸形数据与溢出。

**Non-Goals:**

- 不在此 change 中实现密钥轮换或密钥管理服务；密钥由配置传入。
- 不实现 OAuth2/JWT 等复杂认证；仅 API Key 简单校验。
- 不改变 player-client 的协议格式（加密为可选能力，通过 flags 或配置开关控制）。

## Decisions

1. **AES-GCM 替代 AES/ECB**
   - MessageEncryptor 内部实现改为 AES/GCM/NoPadding；使用 12-byte IV（随机生成，与密文一起传输）；密钥仍为 16 或 32 字节；提供 encrypt(byte[])/decrypt(byte[]) 接口，密文格式为 IV || ciphertext || tag（或等效）。
   - **理由**：GCM 提供认证加密，防篡改；IV 随机可避免 ECB 的重复明文问题。

2. **编解码 Pipeline 集成点**
   - 在 GameMessageEncoder 中：若消息头 flags 标明加密，则对 body 调用 MessageEncryptor.encrypt，再写入；在 GameMessageDecoder 中：若 flags 标明加密，则先调用 MessageEncryptor.decrypt 再解析 body。
   - **理由**：与现有 flags 设计一致；player-client 需同步支持加密标志与 GCM 加解密。
   - **兼容**：可通过配置或 flags 约定，未设置加密时保持明文，便于渐进迁移。

3. **REST API Key 认证**
   - 在 login-service、center-service 的 HTTP 处理链中增加 Filter 或等效拦截逻辑：从 Header（如 `X-API-Key` 或 `Authorization: Bearer <key>`）提取 API Key，与配置的合法 Key 比对；未携带或错误则返回 401。
   - **理由**：内部服务间调用（如 Gate 调用 Login）需携带 Key；不依赖 Spring Security，使用轻量 Filter 即可。
   - **配置**：Key 通过配置或环境变量传入，不硬编码。

4. **消息体长度与校验**
   - WebSocket 文本消息：在 GateNettyWebSocketHandler 或前置 Handler 中限制 frame.text() 长度（如 64KB），超长则关闭连接或返回错误。
   - 字段校验：对 PlayerMessage、LoginRequest 等 DTO 使用 Jakarta Bean Validation（@NotNull、@Size、@Min 等）或自定义校验逻辑；校验失败返回 400 或等效错误码。
   - **理由**：防止超大 JSON 导致内存问题；防止 null、空字符串、越界数值等畸形数据进入业务逻辑。

5. **加密与校验的可配置性**
   - 通过配置开关控制是否启用加密（如 `gate.security.encryption.enabled`）；未启用时编解码不调用 MessageEncryptor。
   - API Key 校验可配置为允许空（开发环境）或强制校验（生产）。
   - **理由**：便于开发调试与渐进部署。

## Risks / Trade-offs

- **[风险]** 升级 GCM 后与旧 player-client 不兼容 → mitigation：通过 flags 或版本协商，旧客户端不发加密标志则保持明文；新客户端与 Gate 同时升级后启用加密。
- **[权衡]** API Key 在 Header 中传输，若 HTTP 未启用 TLS 则可能被窃听 → 生产环境应配合 P005 TLS，API Key 仅用于内部网络。
- **[风险]** Bean Validation 增加依赖与复杂度 → 若项目已使用 validation，可直接复用；否则可用轻量自定义校验。

## Migration Plan

- **实现顺序**：1) MessageEncryptor 改为 AES-GCM；2) 在 GameMessageEncoder/Decoder 中集成加密逻辑（含配置开关）；3) 为 login/center 添加 API Key Filter；4) 为 WebSocket 增加消息长度限制；5) 为 DTO 增加 validation；6) 更新 player-client 支持加密（若本 change 包含）。
- **部署**：需同步配置 API Key；若启用加密需确保 Gate 与 player-client 版本兼容；建议先部署 Gate，player-client 暂不启用加密，验证无误后再全量启用。
- **回滚**：关闭加密开关、移除 API Key 校验或配置为宽松模式；恢复 MessageEncryptor 为 ECB（不推荐，仅紧急回滚时考虑）。

## Open Questions

- player-client 的加密支持是否在本 change 范围内？建议：Gate 先完成集成与 GCM 实现，player-client 可独立迭代或同批交付。
- API Key 的存储方式：配置文件、环境变量、还是密钥管理服务？建议先支持配置/环境变量。

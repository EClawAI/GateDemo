## Context

- **当前状态**：WebSocket 使用 ws:// 明文传输，gRPC 使用 usePlaintext() 未启用 TLS；生产环境中敏感数据（token、会话、游戏指令）可能被窃听或篡改。
- **问题**：无 TLS 时存在中间人攻击风险；合规与安全审计要求生产环境必须加密。
- **约束**：不使用 Spring Boot SSL 配置；在 Netty 与 gRPC 原生层面配置 TLS；支持开发环境关闭、生产环境开启的开关。

## Goals / Non-Goals

**Goals:**
- WebSocket 升级为 wss://，通过 Netty SslContext 配置
- gRPC 服务端与客户端启用 TLS（SslContextBuilder + 证书）
- 通过配置开关 gate.tls.enabled 控制，开发默认关闭、生产开启

**Non-Goals:**
- 不实现 mTLS（双向认证），仅服务端证书
- 不在此 change 中实现证书自动续期（如 ACME）
- 不改变 HTTP/REST 服务的 TLS 配置（若 login/center 有单独需求可后续扩展）

## Decisions

1. **Netty WebSocket TLS**
   - 使用 io.netty.handler.ssl.SslContextBuilder 加载证书与私钥（PEM 或 PKCS12）
   - 在 WebSocket 管道中，在握手前插入 SslHandler（对入站连接）
   - 当 gate.tls.enabled=false 时，不添加 SslHandler，保持 ws://
   - **理由**：Netty 原生支持，无 Spring 依赖；配置灵活。

2. **gRPC 服务端 TLS**
   - 在 game-service gRPC Server 创建时，通过 NettyServerBuilder.useTransportSecurity(sslContext) 启用 TLS
   - 证书从文件路径或 classpath 加载；通过配置开关控制是否启用
   - **理由**：grpc-java 标准方式；与 Netty 共用 SslContext 构建逻辑。

3. **gRPC 客户端 TLS**
   - Gate 的 GameGrpcClientPool 建连时，当 TLS 开启则使用 ManagedChannelBuilder 的 useTransportSecurity() 或 equivalent，传入 SslContext
   - 替代 usePlaintext()；可配置跳过主机名验证（仅开发/自签名证书时）
   - **理由**：端到端加密；生产环境 Gate↔Game 通信加密。

4. **gate.tls.enabled 开关**
   - 默认 false（开发环境）；生产环境通过配置或环境变量设为 true
   - 当 true 时：加载证书，WebSocket 与 gRPC 均启用 TLS；当 false 时：保持明文
   - **理由**：开发环境通常无正式证书，避免自签名证书带来的配置复杂度；生产强制开启。

5. **证书配置**
   - 支持证书文件路径：cert-path、key-path；或 PKCS12 的 keystore-path、keystore-password
   - 支持自签名证书生成脚本或文档说明，供开发/测试环境使用
   - **理由**：生产使用正式 CA 签发证书；开发可用自签名。

## Risks / Trade-offs

- **[风险]** 证书过期导致服务不可用 → 运维需监控证书有效期；文档说明续期流程；后续可集成 ACME。
- **[权衡]** 自签名证书需客户端信任 → 开发环境可配置信任所有证书或跳过验证；生产必须使用正式证书。
- **[风险]** TLS 增加 CPU 开销 → 可接受；现代硬件 TLS 开销较小；必要时可调优 cipher 套件。

## Migration Plan

- **实现顺序**：先实现配置开关与证书加载工具 → game-service gRPC TLS → Gate gRPC Client TLS → Gate WebSocket TLS → 文档与自签名证书说明。
- **部署**：开发环境默认关闭，无需证书；生产需提前准备证书文件并配置路径；首次部署建议在测试环境验证 wss 与 gRPC TLS 互通。
- **回滚**：将 gate.tls.enabled 设为 false 即可回退到明文；需同时重启 gate 与 game 使配置生效。

## Open Questions

- 证书文件路径是否支持 classpath？（如 classpath:keystore.p12）建议支持，便于打包部署。
- login/center 若为 HTTP 服务，是否也需要 TLS？可在此 change 中仅覆盖 gate、game，其他服务后续单独规划。

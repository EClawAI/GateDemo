# P005: TLS 加密

## 1. 配置与证书加载

- [ ] 1.1 新增 gate.tls.enabled 配置项（默认 false），在 GateConfig 中读取
- [ ] 1.2 实现证书加载工具，支持 cert-path + key-path（PEM）或 keystore-path + keystore-password（PKCS12）
- [ ] 1.3 支持从 classpath 加载证书（如 classpath:keystore.p12）
- [ ] 1.4 编写自签名证书生成脚本与文档，供开发/测试环境使用

## 2. game-service gRPC 服务端 TLS

- [ ] 2.1 在 gRPC Server 创建时，根据配置开关决定是否使用 useTransportSecurity
- [ ] 2.2 使用 NettyServerBuilder + SslContextBuilder 加载证书，启用 TLS
- [ ] 2.3 配置项支持开发环境跳过主机名验证（仅自签名证书时）

## 3. Gate gRPC 客户端 TLS

- [ ] 3.1 在 GameGrpcClientPool 建连时，当 gate.tls.enabled=true 时使用 ManagedChannelBuilder.useTransportSecurity()
- [ ] 3.2 传入 SslContext，替代 usePlaintext()
- [ ] 3.3 支持配置跳过主机名验证（仅 dev/自签名证书）

## 4. Gate WebSocket TLS

- [ ] 4.1 在 Netty WebSocket 管道中，于握手前插入 SslHandler（gate.tls.enabled=true 时）
- [ ] 4.2 使用 SslContextBuilder 加载证书，对入站连接应用 TLS
- [ ] 4.3 gate.tls.enabled=false 时不添加 SslHandler，保持 ws://

## 5. 文档与部署

- [ ] 5.1 文档说明生产环境证书准备、路径配置及续期流程
- [ ] 5.2 在 application.yml 中增加 TLS 配置段示例与注释

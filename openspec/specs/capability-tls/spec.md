# capability-tls Specification

## Purpose
TBD - created by archiving change add-tls-encryption. Update Purpose after archive.
## Requirements
### Requirement: WebSocket 支持 wss://

系统 SHALL 在 gate-service 的 Netty WebSocket 服务中支持 TLS；SHALL 使用 SslContextBuilder 加载证书与私钥；MUST 在管道中插入 SslHandler，使客户端可通过 wss:// 连接；SHALL 在 TLS 关闭时保持 ws:// 明文行为。

#### Scenario: TLS 开启时接受 wss 连接
- **WHEN** gate.tls.enabled=true 且证书已配置
- **THEN** WebSocket 服务器在 TLS 握手后处理 WebSocket 升级
- **AND** 客户端使用 wss:// 可成功建立加密连接

#### Scenario: TLS 关闭时保持 ws
- **WHEN** gate.tls.enabled=false
- **THEN** 不添加 SslHandler，客户端使用 ws:// 正常连接
- **AND** 行为与当前实现一致

### Requirement: gRPC 服务端启用 TLS

系统 SHALL 在 game-service 的 gRPC Server 中支持 TLS；SHALL 通过 NettyServerBuilder.useTransportSecurity 或等效方式配置 SslContext；MUST 在 TLS 开启时使用证书，关闭时使用明文；SHALL 从配置文件读取证书路径与密码。

#### Scenario: game-service TLS 开启
- **WHEN** game-service 配置 TLS 开启且证书有效
- **THEN** gRPC 服务仅接受 TLS 连接
- **AND** 明文连接被拒绝

#### Scenario: game-service TLS 关闭
- **WHEN** game-service 配置 TLS 关闭
- **THEN** gRPC 服务接受明文连接
- **AND** 与当前 usePlaintext 行为一致

### Requirement: gRPC 客户端启用 TLS

系统 SHALL 在 Gate 的 GameGrpcClientPool 建连时支持 TLS；SHALL 当 gate.tls.enabled=true 时使用 useTransportSecurity 或等效方式，传入 SslContext；MUST 替代 usePlaintext()；SHALL 支持自签名证书时跳过主机名验证（可配置，仅开发环境）。

#### Scenario: Gate 连接 Game 使用 TLS
- **WHEN** gate.tls.enabled=true 且 Game 已启用 TLS
- **THEN** Gate 与 Game 之间的 gRPC 通信加密
- **AND** 连接建立成功，业务调用正常

#### Scenario: Gate TLS 关闭时明文连接
- **WHEN** gate.tls.enabled=false
- **THEN** Gate 使用 usePlaintext 连接 Game
- **AND** 与当前行为一致

### Requirement: gate.tls.enabled 配置开关

系统 SHALL 支持 gate.tls.enabled 配置项；SHALL 默认值为 false（开发环境）；MUST 当为 true 时加载证书并启用 WebSocket 与 gRPC TLS；SHALL 支持证书路径、私钥路径等子配置项。

#### Scenario: 开发环境默认关闭
- **WHEN** 未配置或 gate.tls.enabled=false
- **THEN** WebSocket 与 gRPC 均使用明文
- **AND** 无需提供证书文件即可启动

#### Scenario: 生产环境开启
- **WHEN** gate.tls.enabled=true 且证书已配置
- **THEN** WebSocket 与 gRPC 均启用 TLS
- **AND** 证书加载失败时启动失败并报错


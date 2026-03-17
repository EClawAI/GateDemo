## Why

当前通信未加密，存在安全隐患：
- WebSocket 使用 ws:// 明文传输
- gRPC 使用 usePlaintext()，未启用 TLS
- 生产环境需支持 wss:// 与 gRPC TLS 加密

## What Changes

为 WebSocket 和 gRPC 通道增加 TLS 支持：
- WebSocket 升级为 wss://
- gRPC 客户端与服务端启用 TLS
- 通过配置开关（如 gate.tls.enabled）控制，支持开发/生产环境切换

## 核心功能

1. **Netty SslContext 配置**
   - 加载证书与私钥
   - 为 WebSocket Server 配置 SSL Handler

2. **gRPC TLS**
   - gRPC Server 启用 TLS
   - gRPC Client 使用 TLS 连接（替代 usePlaintext）

3. **gate.tls.enabled 开关**
   - 开发环境默认关闭，使用明文
   - 生产环境开启，强制加密

## Impact

- 影响 `gate-service` Netty WebSocket 初始化、gRPC Client 配置
- 影响 `game-service` gRPC Server 配置
- 需准备证书文件或支持自签名证书生成

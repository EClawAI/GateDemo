## Why

当前Gate与Game之间的gRPC通信使用JSON字符串作为消息体（`string body = 7`），这种方式存在性能开销：每次消息都需要进行JSON序列化和反序列化。随着游戏消息量增加，这种方式将成为性能瓶颈。改为使用gRPC Stream可以建立持久连接，支持双向流式通信，减少连接建立开销，提升吞吐量。

## What Changes

1. **修改proto定义**：将消息体从JSON字符串改为二进制（bytes）格式
2. **新增Stream通信**：使用gRPC Bidirectional Stream实现Gate↔Game持久连接
3. **二进制序列化**：使用MessagePack或Protobuf作为消息体序列化格式
4. **更新客户端池**：支持Stream连接管理

## Capabilities

### New Capabilities
- `capability-grpc-stream`: gRPC双向流通信能力

### Modified Capabilities
- 无

## Impact

- 影响 `gate-service` 的 `grpc/GameGrpcClientPool` 类
- 影响 `proto/game_service.proto` 的消息定义
- 影响 `game-service` 的gRPC服务端实现

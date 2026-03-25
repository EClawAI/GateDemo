## MODIFIED Requirements

### Requirement: gRPC GameMessage 使用 int32 msg_id
`game_service.proto` 的 `GameMessage` SHALL 将 `string msg_type = 4` 替换为 `int32 msg_id = 4`，Gate 和 Game 之间通过整数 messageId 传递消息类型。

#### Scenario: Gate 转发上行消息
- **WHEN** Gate 收到客户端消息 messageId=N 需转发至 Game
- **THEN** Gate 构建 `GameMessage` 时设置 `msg_id = N`（int32），不再设置 msg_type

#### Scenario: Game 下行推送
- **WHEN** Game 通过 gRPC 双向流向 Gate 推送下行消息
- **THEN** `GameMessage.msg_id` 为下行消息的 CRC32 id，Gate 直接将此 id 写入客户端协议头

### Requirement: Gate 侧适配 msg_id
gate-service 的 `GameServiceRouter`、`GameGrpcClientPool`、`GrpcStreamConfig` SHALL 将所有 `msgType`(string) 参数和用法替换为 `msgId`(int)。

#### Scenario: GameServiceRouter 转发
- **WHEN** `GameServiceRouter.forward()` 被调用
- **THEN** 从 `WrappedMessage` 头部取 messageId(int)，通过 `GameGrpcClientPool.sendGameMessageViaStream(gameId, playerId, msgId, seq, body)` 发送

#### Scenario: 下行流接收
- **WHEN** `GrpcStreamConfig` 收到 Game 推送的 `GameMessage`
- **THEN** 从 `message.getMsgId()` 获取 int32 id，写入 `WrappedMessage` 协议头

### Requirement: Gate MessageDispatcher 使用 Registry 查 ID
gate-service 的 `MessageDispatcher.registerDefaultHandlers()` SHALL 从 `MessageRouteRegistry.getIdByName()` 查询消息 id，替代硬编码的 `0x1001`、`0x2001`。

#### Scenario: 动态查询消息 ID
- **WHEN** `MessageDispatcher` 初始化注册默认 handler
- **THEN** 使用 `MessageRouteRegistry.getIdByName("AuthRequest")` 和 `MessageRouteRegistry.getIdByName("ClientHeartbeat")` 获取 id

### Requirement: gen_proto.py 排除 gRPC 传输协议
`tools/gen_proto.py` SHALL 排除 `game_service.proto`，不为其中的 message 分配消息 ID。

#### Scenario: 生成后 JSON 不含 gRPC 内部消息
- **WHEN** 运行 gen_proto.py
- **THEN** `message_registry.json` 不包含 `GameMessage`、`GameResponse`、`HeartbeatRequest`、`HeartbeatResponse`

### Requirement: 下行消息 body 统一 protobuf 二进制
Game 服务下行消息的 body SHALL 为 protobuf 序列化的二进制（`MessageLite.toByteArray()`），不再使用 JSON Map。

#### Scenario: 下行消息全二进制
- **WHEN** Game handler 通过 MessageSender 发送下行消息
- **THEN** body 为 `message.toByteArray()`，Gate 收到后直接写入客户端协议的 body 段，无 JSON 转换

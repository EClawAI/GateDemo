## Why

当前 Gate→Game 的 gRPC 传输仍使用字符串 `msg_type` 而非消息 ID，Game 服务内部用 switch-case 字符串分发，消息体仍是 JSON 而非 protobuf 二进制。与客户端↔Gate 已建立的二进制协议不一致，且随着消息量增长，switch-case 和 JSON 解析不可维护。需要统一全链路为 messageId + protobuf 二进制，并在 core 模块建立通用消息处理框架，供 Game/Chat 等后端服务二次封装使用。

## What Changes

- **BREAKING** 修改 `game_service.proto` 的 `GameMessage`：`string msg_type` 替换为 `int32 msg_id`
- **新增** core 模块消息处理框架：`IMessageHandler<T>`、`MessageContext`、`MessageSender`、`@MessageMapping` 注解、`MessageHandlerRegistry`、`MessageHandlerScanner`
- **新增** game-service 二次封装：`IGameHandler<T>`、`GameMessageContext`（预加载 PlayerData、dirty 标记）、`GameMessageDispatcher`（前置加载→分发→后置存盘）
- **修改** gate-service `GameServiceRouter` / `GameGrpcClientPool`：传输 `msgId`（int）代替 `msgType`（string）
- **修改** gate-service `GrpcStreamConfig`：接收下行 `msgId` 并写入协议头
- **修改** gate-service `MessageDispatcher`：硬编码 `0x1001`/`0x2001` 改为从 `MessageRouteRegistry` 查 ID
- **删除** game-service 旧的 `GameMessageHandler` switch-case 分发和 `OutgoingMessageSink`（string msgType + JSON Map）
- **删除** game-service `UpstreamConsumerService`（适配层不再需要）
- **修改** `gen_proto.py`：排除 `game_service.proto`（gRPC 传输协议）不纳入消息 ID 分配
- **修改** game-service 下行消息：body 改为 protobuf 二进制（与上行对齐）

## Capabilities

### New Capabilities
- `core-message-framework`: core 模块通用消息处理框架——IMessageHandler、MessageContext、@MessageMapping 注解自动扫描、MessageHandlerRegistry、MessageSender
- `game-handler-layer`: game-service 对 core 框架的二次封装——IGameHandler、GameMessageContext（预加载 Player）、GameMessageDispatcher（前置/后置生命周期）

### Modified Capabilities
- `capability-grpc-stream`: gRPC GameMessage 从 string msg_type 改为 int32 msg_id，body 统一为 protobuf 二进制

## Impact

- `proto/game_service.proto` — GameMessage 字段变更（**BREAKING**：所有使用 msg_type 的代码需迁移）
- `core` 模块 — 新增 message 子包，新增 spring-context 依赖
- `game-service` 模块 — handler 子包新增，旧 GameMessageHandler/OutgoingMessageSink/UpstreamConsumerService 删除，GameGrpcServer 对接新 Dispatcher
- `gate-service` 模块 — GameServiceRouter、GameGrpcClientPool、GrpcStreamConfig、MessageDispatcher 适配 int msgId
- `tools/gen_proto.py` — 增加排除文件逻辑
- `common/config/message_registry.json` — 重新生成（排除 gRPC 内部消息后条目减少）

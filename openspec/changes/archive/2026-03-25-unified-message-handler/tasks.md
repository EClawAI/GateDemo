## 1. Proto 与脚本修改

- [x] 1.1 修改 `game_service.proto`：`GameMessage` 的 `string msg_type = 4` 替换为 `int32 msg_id = 4`
- [x] 1.2 修改 `tools/gen_proto.py`：增加 `EXCLUDE_FILES` 排除 `game_service.proto`，重新生成 `message_registry.json`
- [x] 1.3 运行 `tools/gen_proto.sh` 验证 JSON 正确（不含 GameMessage/GameResponse 等 gRPC 内部消息）

## 2. Core 消息处理框架

- [x] 2.1 创建 `core/src/.../message/MessageContext.java`：基础上下文（playerId, gameId, messageId, sequence, sender），支持子类继承
- [x] 2.2 创建 `core/src/.../message/MessageSender.java`：下行发送接口，`send(long playerId, MessageLite msg)` / `broadcast(int gameId, MessageLite msg)`
- [x] 2.3 创建 `core/src/.../message/IMessageHandler.java`：泛型接口 `<T extends MessageLite>`
- [x] 2.4 创建 `core/src/.../message/MessageMapping.java`：`@MessageMapping(Class<? extends MessageLite>)` 注解
- [x] 2.5 创建 `core/src/.../message/MessageHandlerRegistry.java`：`int messageId → { Parser, IMessageHandler }` 映射，提供 `register(Class, handler)` 和 `getHandler(int)`
- [x] 2.6 创建 `core/src/.../message/MessageHandlerScanner.java`：Spring `ApplicationContextAware` 自动扫描 `@MessageMapping` Bean 注册到 Registry
- [x] 2.7 更新 `core/pom.xml`：spring-context 已由 spring-boot-starter 提供，无需额外添加

## 3. Game Service 二次封装

- [x] 3.1 创建 `game-service/src/.../handler/GameMessageContext.java`：继承 MessageContext，添加 `PlayerData player` 和 `dirty` 标记
- [x] 3.2 创建 `game-service/src/.../handler/IGameHandler.java`：继承 IMessageHandler，定义 `handle(GameMessageContext, T)`，提供 bridge default 方法
- [x] 3.3 创建 `game-service/src/.../handler/GameMessageDispatcher.java`：前置加载 player → 查 registry + parseFrom → 调用 handler → 后置 dirty 存盘
- [x] 3.4 创建 `game-service/src/.../handler/GameMessageSender.java`：实现 MessageSender，通过 gRPC StreamObserver 下发（从 class simpleName 查 id，toByteArray 序列化）

## 4. 业务 Handler 迁移

- [x] 4.1 将 `GameMessageHandler` 中的 battle.move 逻辑迁移为 `BattleMoveHandler`（`@MessageMapping(CgBattleMove.class)` + `IGameHandler`）
- [x] 4.2 将 battle.attack 逻辑迁移为 `BattleAttackHandler`
- [x] 4.3 将 player.login / player.save / echo / broadcast 等逻辑按需迁移为独立 Handler（可暂用临时 proto 消息定义）
- [x] 4.4 删除旧 `GameMessageHandler`、`OutgoingMessageSink`、`UpstreamConsumerService`

## 5. Gate 侧适配

- [x] 5.1 修改 `GameServiceRouter`：传 `messageId`(int) 代替 `msgType`(string)
- [x] 5.2 修改 `GameGrpcClientPool`：`sendGameMessageViaStream` / `sendGameMessage` 参数从 `String msgType` 改为 `int msgId`，构建 `GameMessage` 时设置 `msg_id`
- [x] 5.3 修改 `GrpcStreamConfig`：接收下行时从 `message.getMsgId()` 获取 int id 写入协议头
- [x] 5.4 修改 `GameGrpcServer`（game-service）：从 `request.getMsgId()` 获取消息 id 传给 Dispatcher
- [x] 5.5 修改 `MessageDispatcher`：`registerDefaultHandlers` 中硬编码 `0x1001`/`0x2001` 改为从 `MessageRouteRegistry.getIdByName()` 查询

## 6. 验证

- [x] 6.1 运行 `mvn compile` 确认全项目编译通过
- [x] 6.2 检查无对旧类（GameMessageHandler、OutgoingMessageSink、UpstreamConsumerService、msg_type）的残留引用

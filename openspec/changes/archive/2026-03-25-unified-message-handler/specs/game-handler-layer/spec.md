## ADDED Requirements

### Requirement: GameMessageContext 扩展上下文
game-service SHALL 提供 `GameMessageContext extends MessageContext`，包含 `PlayerData player`（由 Dispatcher 前置加载）和 `boolean dirty`（脏标记，handler 调用 `markDirty()` 标记）。

#### Scenario: Handler 直接访问预加载的 Player
- **WHEN** handler 的 handle 方法被调用
- **THEN** `ctx.getPlayer()` 返回已从 `PlayerDataManager.load(playerId)` 加载的 PlayerData 对象

#### Scenario: 脏标记触发后置存盘
- **WHEN** handler 中调用 `ctx.markDirty()` 后分发流程完成
- **THEN** `GameMessageDispatcher` 在后置阶段调用 `playerDataManager.markDirty(playerId)`

### Requirement: IGameHandler 二次封装接口
game-service SHALL 提供 `IGameHandler<T extends MessageLite> extends IMessageHandler<T>`，重新定义 `void handle(GameMessageContext ctx, T message)`，并提供 default bridge 方法将 `MessageContext` 向下转型为 `GameMessageContext`。

#### Scenario: Game handler 开发体验
- **WHEN** 开发者实现 `IGameHandler<CgBattleMove>`
- **THEN** handle 方法签名为 `handle(GameMessageContext ctx, CgBattleMove msg)`，同时满足 core `IMessageHandler` 接口

### Requirement: GameMessageDispatcher 分发编排
game-service SHALL 提供 `GameMessageDispatcher`，接收 gRPC 转发的 `(playerId, gameId, messageId, seq, byte[] body)`，执行：前置（加载 player、构建 GameMessageContext）→ 查 handler + 自动 parseFrom → 调用 handler → 后置（dirty 检查、触发存盘）。

#### Scenario: 正常分发流程
- **WHEN** 收到 messageId 对应已注册的 handler
- **THEN** 依次执行：加载 PlayerData → 构建 GameMessageContext → parseFrom(body) → handler.handle(ctx, msg) → dirty 则 markDirty

#### Scenario: 未注册的 messageId
- **WHEN** 收到的 messageId 在 `MessageHandlerRegistry` 中未注册
- **THEN** 记录 WARN 日志，不执行任何 handler

#### Scenario: Handler 抛出异常
- **WHEN** handler.handle() 抛出异常
- **THEN** 记录 ERROR 日志，不影响后续消息处理

### Requirement: 旧分发代码删除
game-service SHALL 删除 `GameMessageHandler`（switch-case 分发）、`OutgoingMessageSink`（string msgType + JSON Map 接口）、`UpstreamConsumerService`（适配层）。

#### Scenario: 旧代码无引用
- **WHEN** 删除完成后编译
- **THEN** `mvn compile` 成功，无对旧类的引用

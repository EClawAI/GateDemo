## ADDED Requirements

### Requirement: IMessageHandler 泛型接口
core 模块 SHALL 提供 `IMessageHandler<T extends MessageLite>` 接口，定义 `void handle(MessageContext ctx, T message) throws Exception` 方法。

#### Scenario: Handler 接收强类型消息
- **WHEN** 框架调用 handler 的 handle 方法
- **THEN** 参数 `message` 是经 parser 反序列化后的具体 proto 类型（如 `CgBattleMove`），而非 `byte[]`

### Requirement: MessageContext 基础上下文
core 模块 SHALL 提供 `MessageContext` 类，包含 `playerId`、`gameId`、`messageId`、`sequence`、`MessageSender sender` 字段，支持子类继承扩展。

#### Scenario: 子类扩展上下文
- **WHEN** game-service 定义 `GameMessageContext extends MessageContext` 并添加 `PlayerData player` 字段
- **THEN** 编译成功，GameMessageContext 可同时访问基类字段和扩展字段

### Requirement: @MessageMapping 注解
core 模块 SHALL 提供 `@MessageMapping` 注解，`value()` 为 `Class<? extends MessageLite>`，标记在 Handler 类上声明其处理的 proto 消息类型。

#### Scenario: 注解声明消息类型
- **WHEN** Handler 类标注 `@MessageMapping(CgBattleMove.class)`
- **THEN** 框架可从注解中提取 `CgBattleMove.class`

### Requirement: MessageHandlerRegistry 存储
core 模块 SHALL 提供 `MessageHandlerRegistry`，维护 `int messageId → { Parser, IMessageHandler }` 映射。

#### Scenario: 按 proto class 注册
- **WHEN** 调用 `registry.register(CgBattleMove.class, handler)`
- **THEN** 从 class simpleName "CgBattleMove" 查 `MessageRouteRegistry` 获取 id，从 class 获取 parser，存入映射

#### Scenario: 按 messageId 查找
- **WHEN** 调用 `registry.getHandler(messageId)`
- **THEN** 返回对应的 parser 和 handler；未注册时返回 null

#### Scenario: 消息名未在 RouteRegistry 中注册
- **WHEN** proto class 的 simpleName 在 `MessageRouteRegistry` 中找不到对应 id
- **THEN** 记录 WARN 日志，跳过该 handler 的注册

### Requirement: MessageHandlerScanner 自动扫描
core 模块 SHALL 提供 `MessageHandlerScanner`，在 Spring 容器启动后自动扫描所有标注 `@MessageMapping` 的 Bean，调用 `MessageHandlerRegistry.register` 注册。

#### Scenario: 启动自动注册
- **WHEN** Spring 应用启动完成
- **THEN** 所有 `@Component` + `@MessageMapping` 的 Bean 被自动注册到 `MessageHandlerRegistry`，日志输出注册数量

### Requirement: MessageSender 下行发送接口
core 模块 SHALL 提供 `MessageSender` 接口，包含 `send(long playerId, MessageLite message)` 和 `broadcast(int gameId, MessageLite message)` 方法，框架从 `message.getClass().getSimpleName()` 查 `MessageRouteRegistry` 获取 messageId。

#### Scenario: 按对象类型自动查 id
- **WHEN** 调用 `sender.send(playerId, scBattlePosition)` 且 `scBattlePosition` 类型为 `ScBattlePosition`
- **THEN** 框架从 simpleName "ScBattlePosition" 查到 messageId，将 id 和序列化后的 body 通过 gRPC 发回 Gate

## Context

当前全链路消息流存在"断层"：客户端↔Gate 已使用 16 字节二进制协议头（messageId int32 + protobuf body），但 Gate↔Game 的 gRPC `GameMessage` 仍传 `string msg_type` + JSON body，Game 服务内部用 switch-case 字符串分发。消息量预期增长到几百条，需要统一为 id 驱动 + protobuf 二进制全链路，同时建立可扩展的 handler 框架。

现有代码路径：
- Gate 侧：`GateNettyWebSocketHandler` → `ServiceRouterManager` → `GameServiceRouter` → `GameGrpcClientPool.sendGameMessageViaStream(gameId, playerId, msgType, seq, rawBody)`
- Game 侧：`GameGrpcServer.GameServiceImpl` → `GameMessageHandler.handleGameMessage(playerId, gameId, msgType, seq, bodyBytes)` → switch-case
- 下行：`OutgoingMessageSink.emit(playerId, gameId, msgType, seq, Map<String,Object> body)` → JSON 序列化 → gRPC → Gate

## Goals / Non-Goals

**Goals:**
- gRPC `GameMessage` 使用 `int32 msg_id` 替代 `string msg_type`，全链路 id 驱动
- core 模块提供通用消息处理框架：注解自动扫描注册、泛型 handler、自动 protobuf 反序列化
- game-service 对 core 框架二次封装：预加载 PlayerData、dirty 标记、自动存盘
- 下行消息统一 protobuf 二进制 + messageId
- gen_proto.py 排除 gRPC 传输协议（game_service.proto）
- Gate 侧硬编码消息 ID 改为从 Registry 查询

**Non-Goals:**
- 不重构 Gate 内部的消息处理架构（Gate 以路由转发为主，现有 switch 足够）
- 不新建 chat-service（仅在框架层面做兼容性预留）
- 不改动客户端↔Gate 的二进制协议（已完成）

## Decisions

### D1: gRPC GameMessage 直接替换 msg_type 为 msg_id

**决定**：`game_service.proto` 的 `GameMessage.msg_type` (string, field 4) 替换为 `msg_id` (int32, field 4)。

**理由**：全链路 id 一致，避免 Gate 查 name → 传字符串 → Game 再查回 id 的冗余转换。proto field number 保持 4 不变（但类型从 string 变 int32 是 **BREAKING**，需要 Gate 和 Game 同时部署）。

**替代方案**：
- 新增字段并存：兼容但冗余，两个字段容易不一致
- 保持字符串：与全链路 id 统一的目标矛盾

### D2: 三层架构——core 机制 / service 封装 / 业务 handler

**决定**：
- **core** 提供纯机制：`IMessageHandler<T>` 接口、`MessageContext` 基类、`@MessageMapping` 注解、`MessageHandlerRegistry`（id→parser+handler 存储）、`MessageHandlerScanner`（Spring 自动扫描）、`MessageSender` 下行接口
- **game-service** 做二次封装：`IGameHandler<T>` 继承 core 接口并桥接 `GameMessageContext`，`GameMessageDispatcher` 编排前置（加载 player）→分发→后置（dirty 存盘）
- **Gate** 保留现有架构，不使用 core handler 框架

**理由**：Gate 90% 是路由转发不需要 handler 模式；Game/Chat 每条消息都要解析 body，注解自动注册 + 强类型 + 预加载 player 价值大；core 只做机制不做策略，各服务封装自己的前置/后置逻辑。

### D3: Handler 注册方式为 @MessageMapping(ProtoClass.class) + Spring 自动扫描

**决定**：Handler 类标注 `@MessageMapping(CgBattleMove.class)` + `@Component`，框架启动时自动扫描、从 proto class 推导消息名和 parser、从 Registry 查 id、注册到 Map。

**理由**：
- Proto class 是消息定义的权威来源，simpleName 即消息名
- `getDefaultInstance().getParserForType()` 获取 parser，框架自动反序列化
- Handler 拿到强类型 proto 对象，编译期类型安全
- 开发者只需写一个类、标一个注解，零注册代码

**替代方案**：
- 手动注册 `registry.register(...)` — 消息多了容易遗漏
- Lambda/方法引用 — 无类型推导，无法自动 parse

### D4: 下行发送 API 只传对象，框架从 getClass() 推导

**决定**：`MessageSender.send(playerId, resp)` / `broadcast(gameId, resp)`，框架内部从 `resp.getClass().getSimpleName()` 查 Registry 得到 msgId。

**理由**：Proto 生成类无继承多态，`getClass()` 一定是具体类；API 更简洁；与上行 `@MessageMapping(Class)` 对称。

### D5: gen_proto.py 排除 game_service.proto

**决定**：在脚本中维护排除列表 `EXCLUDE_FILES = ["game_service.proto"]`，跳过 gRPC 传输协议的 message 定义。

**理由**：`GameMessage`、`GameResponse`、`HeartbeatRequest`、`HeartbeatResponse` 是 Gate↔Game 的传输协议，不是客户端协议消息，不应分配客户端可见的 messageId。

## Risks / Trade-offs

- **[风险] gRPC proto 变更是 BREAKING** → Gate 和 Game 需同时部署。在生产环境可通过蓝绿部署或灰度解决；开发环境影响可控。
- **[风险] core 新增 spring-context 依赖** → 为了 `ApplicationContextAware` 扫描 `@Component`。core 已依赖 common（有 protobuf），spring-context 是 Spring Boot 应用的固有依赖，不额外增加负担。
- **[权衡] GameMessageContext 向下转型** → `IGameHandler` 的 bridge 方法将 `MessageContext` cast 为 `GameMessageContext`。类型安全由 `GameMessageDispatcher` 保证（只传 `GameMessageContext`），handler 永远不会收到错误类型。
- **[权衡] Gate 保留独立架构** → 两套 handler 机制并存（Gate 的 `MessageDispatcher` + core 的 `MessageHandlerRegistry`），但职责清晰不交叉。

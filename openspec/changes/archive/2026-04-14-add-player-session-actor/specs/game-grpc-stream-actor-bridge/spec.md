# game-grpc-stream-actor-bridge Specification (delta)

## MODIFIED Requirements

### Requirement: 桥接路径调用既有分发器

对每条上行流帧，实现 SHALL **不得**在 gRPC/Netty 回调线程上调用 **`GameMessageDispatcher.dispatch`**。实现 SHALL 将工作从 **每流桥接 Typed Actor**（StreamIngress）移交至按 **`playerId`** 寻址的 **`PlayerSessionActor`**；**`GameMessageDispatcher.dispatch(long playerId, int messageId, int seq, byte[] body)`**（或经评审的等价签名）SHALL 在 **`PlayerSessionActor`** 的 **Actor 执行线程**上调用，以保持与现有 **MessageHandlerRegistry**、**PlayerDataManager** 行为一致。

#### Scenario: 载荷字段与 proto 一致

- **WHEN** 一条 `GameMessage` 到达并经桥接路径处理
- **THEN** 传入 `dispatch` 的 `playerId`、`messageId`、`seq`、`body` 与 proto 字段语义一致（`body` 为消息体字节）

#### Scenario: dispatch 在非 IO 线程

- **WHEN** 一条 `GameMessage` 经双向流到达
- **THEN** `dispatch` 未在 gRPC `StreamObserver` 回调线程上执行

### Requirement: 自动化测试覆盖桥接行为

项目 SHALL 提供 **自动化测试** 验证：(1) 双向流入站 **不**在 gRPC 回调线程上调用 `GameMessageDispatcher.dispatch`（可通过 **测试替身/间谍** 或线程断言）；(2) 消息经桥接后 **`GameMessageDispatcher.dispatch` 被调用**且参数正确（**调用可发生在 `PlayerSessionActor` 等 Typed Actor 路径上**）。测试 **可**使用 **进程内模拟 StreamObserver** 或 **grpc 测试基础设施**，**不强制**真实网络。

#### Scenario: 测试通过且稳定

- **WHEN** 执行 `game-service` 相关测试套件
- **THEN** 上述测试用例通过
- **AND** 无不加界定的 `Thread.sleep` 作为唯一同步手段（或睡眠有明确上界与注释）

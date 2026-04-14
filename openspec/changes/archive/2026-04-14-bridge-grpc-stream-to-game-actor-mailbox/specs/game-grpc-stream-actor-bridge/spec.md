# game-grpc-stream-actor-bridge (Delta)

## Purpose

规定 **game-service** 如何将 **Gate–Game 双向流** 的上行 `GameMessage` 从 **gRPC 回调线程**移交至 **Pekko Typed Actor 邮箱**处理，并与 **流生命周期**、**应用侧背压** 对齐。

## ADDED Requirements

### Requirement: 双向流 onNext 不得在游戏业务路径上阻塞 IO 线程

对 **`GameService.StreamCommunication`** 服务端 **`StreamObserver<GameMessage>`** 的 **`onNext`**：`game-service` SHALL **不得**在该回调中直接调用 **`GameMessageDispatcher.dispatch`**（或与之等价的同步业务处理入口）。实现 SHALL **仅**执行轻量操作（如构造不可变入站命令、记录指标）并将工作移交至 **本规格定义的桥接 Actor 路径**。

#### Scenario: 回调中间接执行业务

- **WHEN** Gate 经双向流发送一条 `GameMessage`
- **THEN** gRPC `onNext` 在返回前不执行 `PlayerData` 加载、handler 执行或同步存盘逻辑
- **AND** 上述业务在 **Actor 默认调度器**（或设计文档声明的等价执行上下文）上运行

### Requirement: 每流桥接 Actor 与流生命周期绑定

`game-service` SHALL 为 **每一个**成功建立的 **`streamCommunication`** 会话维护 **恰好一个** 桥接 Typed Actor（或设计文档中等价的、可审计的单写者），用于串行处理该流上的入站帧。当该 RPC 流 **结束**（`onCompleted` 或 `onError`）时，实现 SHALL **停止**对应桥接 Actor 并释放相关引用，避免 Actor 泄漏。

#### Scenario: 流关闭后不再处理入站

- **WHEN** 该双向流已 `onCompleted` 或 `onError`
- **THEN** 与该流绑定的桥接 Actor 被停止或已进入终止状态
- **AND** 不再接受新的入站帧处理请求（除非新流建立新 Actor）

### Requirement: 桥接路径调用既有分发器

桥接 Actor SHALL 在 **Actor 执行线程**上调用既有 **`GameMessageDispatcher.dispatch(long playerId, int messageId, int seq, byte[] body)`**（或经评审的等价签名），以保持与现有 **MessageHandlerRegistry**、**PlayerDataManager** 行为一致。

#### Scenario: 载荷字段与 proto 一致

- **WHEN** 一条 `GameMessage` 到达并被桥接处理
- **THEN** 传入 `dispatch` 的 `playerId`、`messageId`、`seq`、`body` 与 proto 字段语义一致（`body` 为消息体字节）

### Requirement: 应用侧邮箱背压或限流策略

`game-service` SHALL 为桥接 Actor 定义 **有界邮箱或等价限流**，并在 **溢出**时采用 **设计文档**中声明的策略（例如丢弃并记录、可配置）。实现 SHALL 提供 **可观测性**占位（至少 **日志或计数**），以便压测时发现处理跟不上。

#### Scenario: 溢出可被检测

- **WHEN** 入站速率持续高于 Actor 处理能力导致邮箱达到边界
- **THEN** 系统按声明策略处理溢出且不无限增长内存队列
- **AND** 产生可观测记录（日志或指标占位）

### Requirement: 自动化测试覆盖桥接行为

项目 SHALL 提供 **自动化测试** 验证：(1) 双向流入站 **不**在 gRPC 回调线程上调用 `GameMessageDispatcher.dispatch`（可通过 **测试替身/间谍** 或线程断言）；(2) 消息经桥接后 **`dispatch` 被调用**且参数正确。测试 **可**使用 **进程内模拟 StreamObserver** 或 **grpc 测试基础设施**，**不强制**真实网络。

#### Scenario: 测试通过且稳定

- **WHEN** 执行 `game-service` 相关测试套件
- **THEN** 上述测试用例通过
- **AND** 无不加界定的 `Thread.sleep` 作为唯一同步手段（或睡眠有明确上界与注释）

# Proposal: Bridge gRPC Stream to Game Actor Mailbox

## Why

[`docs/pekko-game-actor-openspec-roadmap.md`](../../../docs/pekko-game-actor-openspec-roadmap.md) **阶段 2** 要求将 Gate–Game **双向流**上的上行帧从 **gRPC/Netty 回调线程**迁移到 **Pekko Actor 邮箱**中处理，避免在 IO 线程上执行 `GameMessageDispatcher`（含加载玩家、handler、可能存盘）。阶段 1 已落地 [`game-pekko-actor-runtime`](../../../openspec/specs/game-pekko-actor-runtime/spec.md)；本变更在 **不改变 `game_service.proto` 与对外可见协议** 的前提下完成 **入站桥接** 与可验证的背压/丢弃策略占位。

## What Changes

- **`GameService.streamCommunication`** 的 **`onNext`**：**不再**同步调用 `GameMessageDispatcher.dispatch`；改为向本变更定义的 **流桥接 Actor**（每流一实例或等价监督结构）**`tell`** 入站载荷（`playerId` / `msgId` / `seq` / `body` 等），在 Actor 线程上执行既有分发逻辑。
- **生命周期**：与 **该 gRPC 流** 绑定：流建立时创建/注册桥接 Actor，流 **`onError` / `onCompleted`** 时停止并清理，避免泄漏。
- **背压**：在设计与实现中明确 **邮箱有界或限流** 策略（与 [`docs/backpressure-design.md`](../../../docs/backpressure-design.md) 及 HTTP/2 流控区分：**应用侧**处理跟不上时的丢弃/指标/日志），避免无界内存队列。
- **Unary `SendGameMessage`**：本变更 **可保持现状** 或仅文档化「后续与流统一桥接」；**验收以双向流为主**（见 `tasks.md`）。
- **非目标**：完整 **PlayerSessionActor**、**Region/City**、消息信封独立规格（`define-game-actor-message-envelope`）中的 **跨聚合信封类型** 可在后续变更收紧；本阶段载荷可为 **内部 Java 记录/不可变 POJO**，字段与 `GameMessage` 对齐即可。

本变更对 **客户端/Gate** 为 **非 BREAKING**（帧格式不变）；对 **game-service 内部线程模型** 为 **行为等价**（同序处理同一流上的消息由单 Actor 串行保证）。

## Capabilities

### New Capabilities

- `game-grpc-stream-actor-bridge`：**gRPC 双向流入站** 仅做入队；**桥接 Actor** 与 **流生命周期** 绑定；**背压/丢弃** 策略与测试要求。

### Modified Capabilities

- （无）不修改 `capability-grpc-stream` 的 wire 级要求；`game-pekko-actor-runtime` 仍不要求 gRPC 细节，桥接能力由本规格单独描述。

## Impact

- **代码**：`game-service` 的 `GameGrpcServer`（或抽取的流处理类）、`game.pekko` 包下新增桥接 `Behavior` / 监督点；可能需为 `GameMessageDispatcher` 提供 **从 Actor 安全调用** 的注入方式（保持现有业务 handler 不变）。
- **依赖**：不新增 **Pekko Cluster**；仅使用已有 **`pekko-actor-typed`**（及测试 kit）。
- **运维/观测**：可增加邮箱深度、丢弃次数等指标或日志占位（完整指标见路线图阶段 7）。

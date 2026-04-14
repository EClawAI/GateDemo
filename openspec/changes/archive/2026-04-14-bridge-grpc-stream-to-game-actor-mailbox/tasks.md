# Tasks: bridge-grpc-stream-to-game-actor-mailbox

## 1. Root 与桥接 Actor 骨架

- [x] 1.1 扩展或并列于 `GameRootBehavior`：提供 **监督下**创建/停止子 Actor 的能力（例如 `StreamIngress` 工厂接口），保证与现有 `ActorSystem` Bean 兼容。
- [x] 1.2 定义不可变 **入站命令**类型（如 `InboundStreamFrame`），字段覆盖 `dispatch` 所需参数。

## 2. gRPC 与桥接 wiring

- [x] 2.1 修改 `GameGrpcServer`（或抽取类）：在 `streamCommunication` 建立时 **创建**桥接 Actor、注入 `GameMessageDispatcher` 与 `setSender` 所需上下文；在 `onNext` 中 **`tell`** 入站命令，**禁止**直接 `dispatch`。
- [x] 2.2 在流 `onCompleted` / `onError` 中 **停止**桥接 Actor，并视需要清理 `dispatcher.setSender`（与 `GameMessageSender` 生命周期一致）。

## 3. 背压与配置

- [x] 3.1 为桥接 Actor 配置 **有界邮箱**（HOCON 或代码）及 **溢出策略**（与设计一致）。
- [x] 3.2 溢出时 **日志或计数**占位，便于后续接入阶段 7 指标。

## 4. 测试与验证

- [x] 4.1 编写 **单元/组件测试**：断言 `onNext` 回调线程不调用 `dispatch`；`dispatch` 在 Actor 线程（或测试可观测的执行路径）被调用且参数正确。
- [ ] 4.2 （可选）**E2E**：mock client–game 双向流发送至少一条消息，验证端到端无回归（现有集成测试风格一致即可）。

## 5. 文档与衔接

- [x] 5.1 在实现类/JavaDoc 中引用 `design.md` 与 [`docs/backpressure-design.md`](../../../docs/backpressure-design.md) 的关系（HTTP/2 流控 vs 应用邮箱）。
- [x] 5.2 Unary `SendGameMessage` 若未纳入本变更，在代码或 `design.md` 中 **TODO** 指向后续统一桥接。

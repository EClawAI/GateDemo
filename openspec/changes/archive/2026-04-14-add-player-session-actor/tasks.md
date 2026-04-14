## 1. Registry 与 PlayerSession Actor

- [x] 1.1 实现 `PlayerSessionRegistryBehavior`（`RouteInbound`、`StreamClosed`、按 `playerId` spawn/stop、`streamId`↔`playerId` 占用与 refcount）
- [x] 1.2 实现 `PlayerSessionBehavior`（处理入站帧并调用 `GameMessageDispatcher.dispatch`）
- [x] 1.3 Spring 装配：启动时经 `SpawnProtocol` 创建单例 Registry，并向 `GameGrpcServer` / 流构造注入 `ActorRef`

## 2. 桥接与 gRPC 集成

- [x] 2.1 调整 `StreamIngressBehavior`：仅向 Registry 投递路由命令；为每流分配并持有 `streamId`；`Shutdown` 前向 Registry 发送 `StreamClosed`
- [x] 2.2 更新 `GameStreamInboundObserver` / `GameGrpcServer`：构造参数与流结束顺序与 `design.md` 一致
- [x] 2.3 在 `design.md` 中补充 **World 命令类型表占位**（若尚未显式列出）

## 3. 测试与回归

- [x] 3.1 更新 `StreamIngressBehaviorTest` / `GameStreamInboundObserverTest` 以匹配新路由；保持「IO 线程不 dispatch」断言
- [x] 3.2 新增 Registry / PlayerSession 的 TestKit 测试：同 `playerId` 复用、流关闭 refcount 归零后停止会话
- [x] 3.3 运行 `mvn -pl game-service test` 通过

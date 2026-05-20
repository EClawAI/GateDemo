# Why

当前 gate 同时持有 WebSocket（生产）与 TCP（`gate.tcp.enabled=true` 才启动的骨架）两条独立的 Netty pipeline，但：

1. **缺少统一抽象**：`NettyWebSocketServer` 与 `NettyTcpServer` 分别在 `@PostConstruct` 自管线程池与 ServerBootstrap，没有共同接口；
2. **生命周期 / 关停不一致**：`GracefulShutdownManager` 只硬编码停掉 `NettyWebSocketServer`，TCP 端口在 graceful shutdown 期间继续 accept；
3. **业务 handler 在 TCP 旁路上不是同一份**：WS 走 `GateNettyWebSocketHandler`（含 auth/flow/限流/rate-limit），TCP 只走 `TcpMessageHandler`（log + 桩 dispatcher），导致 TCP 接入根本无法做 NEW/RESUME；
4. **未来 KCP / QUIC**：要新增 transport，必须复制 `NettyWebSocketServer` 那一整套 bootstrap，并且改 `GracefulShutdown` / `GateClusterManager` 等多处；
5. **集群注册元数据**：`GateClusterManager` 已经多写一个可选 `tcpPort`，再加一种 transport 就要再加一字段，**不可持续**。

需要建立一个**最小、低风险**的 `GatewayTransport` 抽象，让：
- 现有 WS / TCP 实现「就地包装」即可成为该接口的实例（不改 Netty pipeline，不动 encoder/decoder）；
- 关停 / 健康 / 注册 全部走「transport 列表」；
- 后续 KCP / QUIC 只需新写一个 `GatewayTransport` 实现 + 注册 bean。

# What Changes

## 行为变化

1. **新增 `GatewayTransport` 接口** (`com.clawai.gatedemo.gate.transport.GatewayTransport`)：
   ```java
   String name();           // "websocket", "tcp", "kcp", ...
   boolean isEnabled();
   void start() throws Exception;
   void stopAccepting();    // 停止 accept 新连接，但保留已有 channel
   void stop() throws InterruptedException; // 关停（线程池 / event loop）
   TransportInfo info();    // 对外报告 host / port / scheme，用于集群注册
   ```

2. **新增 `GatewayTransportRegistry`**：Spring `@Component`，注入所有 `GatewayTransport` bean，提供 `transports()` / `byName(name)` 等查询入口。

3. **现有 WS / TCP 服务器适配**：
   - `NettyWebSocketServer` → 实现 `GatewayTransport`，`name()="websocket"`，`info()` 返回 `host:gate.port` + scheme（ws/wss）；
   - `NettyTcpServer` → 实现 `GatewayTransport`，`name()="tcp"`，`info()` 返回 `host:gate.tcp.port`；
   - 二者的 `@PostConstruct` 启动行为保留（即 start() 等价当前 init() 内容），仅追加接口与注册。

4. **GracefulShutdownManager 解耦**：
   - 由「持 `NettyWebSocketServer` 引用直接 stopAccepting」改为「遍历 `GatewayTransportRegistry` 所有 transport」；
   - drain 玩家阶段不变（仍走 `PlayerService`）；
   - stop 阶段调用每个 transport 的 `stop()`。

5. **GateClusterManager 元数据扩展**：
   - 注册 Redis 时不再硬编码 `port` + `tcpPort`，改为遍历 transport 列表，写入 `transports=[{name, host, port, scheme}]` 数组字段；
   - 保留旧 `port` / `tcpPort` 兼容字段（指向 websocket / tcp 实例的 info），方便老 consumer 平滑升级。

6. **TCP 业务 handler 修复（可选 / 最小改动）**：
   - `TcpMessageHandler` 标注 `@Deprecated`，但**不**直接迁移到 `GateNettyWebSocketHandler` —— 这一步留待后续 change（涉及 codec 复用与 JWT/限流注入，超出 B4 范围）；
   - B4 仅说明：未来通过 transport 抽象，TCP pipeline 可以复用同一个 `GateNettyWebSocketHandler` bean，TCP / KCP / QUIC 都能用同一 inbound handler。

7. **KCP / QUIC 扩展点**：
   - 不引入任何外部依赖（kcp4j / netty-incubator-codec-quic 等）；
   - 在 `gate-multi-transport/spec.md` 中明文说明：第三方 transport 只需实现 `GatewayTransport`，由 Spring 自动注入 registry，关停 / 集群注册自动覆盖；
   - 提供示例 `NoopGatewayTransport`（test scope）展示扩展形态。

## 配置 / 兼容性

- **配置变化**：
  - 新增 `gate.transports.<name>.enabled` 命名空间（可选），首期只做映射：
    - `gate.transports.websocket.enabled` ← 始终 true（与现状一致）；
    - `gate.transports.tcp.enabled` ← `gate.tcp.enabled` 同义（保留旧 key 兼容）；
  - 老 key `gate.port` / `gate.tls.*` / `gate.tcp.*` 继续生效，不破坏现有部署。

- **协议 / 二进制格式**：完全不变。

- **客户端 (`player-client`)**：本 change 不要求客户端改造；后续若引入新 transport，再为客户端追加 `PlayerTransport` 镜像抽象（另立 change）。

## Capabilities 影响

新增 capability `gate-multi-transport`（不影响 `gate-resume-reconnect` / `gate-flow-downstream-buffer` / `gate-flow-cross-instance` / `gate-flow-offline-merge`）。

# Impact

- **修改**：`NettyWebSocketServer`、`NettyTcpServer`（仅追加接口实现）、`GracefulShutdownManager`、`GateClusterManager`、`GateConfig`（新增 `TransportsConfig`）、`application.yml`；
- **新增**：`gate/transport/` 包（`GatewayTransport`、`GatewayTransportRegistry`、`TransportInfo` record）；测试 `NoopGatewayTransport`；
- **不修改**：`FlowSessionManager` / `PlayerService` / `OfflineMessageService` / 二进制协议 / 任何 encoder/decoder；
- **可观测性**：新增 metric `gate_transport_state{name, state}`（state ∈ {starting, running, draining, stopped}）；启动日志打印每个 transport 的 `info()`；
- **测试**：
  - 单元：`GatewayTransportRegistryTest`（多个 transport 装配 + byName 查询 + isEnabled 过滤）；
  - 单元：`GracefulShutdownManagerTest`（遍历 transport.stopAccepting + stop）—— 用 `NoopGatewayTransport` stub；
  - 集成（人工 / 可选）：启动后 `/health` + Redis 注册可见 transports 列表。

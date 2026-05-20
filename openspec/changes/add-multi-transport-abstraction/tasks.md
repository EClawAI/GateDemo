## 1. 抽象 + 注册

- [x] 1.1 新建包 `com.clawai.gatedemo.gate.transport`
- [x] 1.2 新增 `GatewayTransport` 接口（含 `name/isEnabled/start/stopAccepting/stop/info/state`）
- [x] 1.3 新增 `TransportInfo` record 与 `TransportState` enum
- [x] 1.4 新增 `GatewayTransportRegistry @Component`，提供 `all/active/byName`

## 2. 现有 WS / TCP 适配

- [x] 2.1 `NettyWebSocketServer implements GatewayTransport`
- [x] 2.2 `NettyWebSocketServer.start()` 增加 idempotent guard（已 running 则直接返回）
- [x] 2.3 `NettyWebSocketServer.info()` 按 TLS 切换 scheme=ws/wss
- [x] 2.4 `NettyTcpServer implements GatewayTransport`
- [x] 2.5 `NettyTcpServer.info()` 报告 host + gate.tcp.port，scheme="tcp"

## 3. GracefulShutdownManager 解耦

- [x] 3.1 注入 `GatewayTransportRegistry`，去掉对 `NettyWebSocketServer` 的直接依赖
- [x] 3.2 `shutdown()` 三阶段：遍历 stopAccepting → drainPlayers → 遍历 stop
- [x] 3.3 单 transport 异常不影响其它 transport（try/catch + log）

## 4. GateClusterManager 注册

- [x] 4.1 注入 `GatewayTransportRegistry`
- [x] 4.2 Redis hash 字段新增 `transports`（JSON 数组）
- [x] 4.3 保留 `port` / `tcpPort` 旧字段一致性

## 5. 可观测性

- [x] 5.1 startup 完成时打印 `event=gate.transports.ready transports=[...]`（注册表 `@EventListener(ApplicationReadyEvent.class)`）
- [ ] 5.2 metrics：`gate_transport_state{name,state}`、`gate_transport_start_total{name}`、`gate_transport_stop_total{name}`（**PENDING**：与现有 micrometer 注册配合，下一迭代加入）
- [ ] 5.3 `/health` 拼接 transports 列表（**PENDING**：增强项；不影响主路径）

## 6. 配置 / 文档

- [x] 6.1 `docs/transport-extension.md` 新增简短扩展指南（如何添 KCP/QUIC）
- [ ] 6.2 `GateConfig` 暴露 `TransportsConfig`（**PENDING**：当前各 transport 已用自身 `*Config` 子段，统一命名空间留作下一次重构）
- [ ] 6.3 `application.yml` 加注释段说明扩展形态（**PENDING**：与 6.2 一起做）

## 7. 测试

- [x] 7.1 单元：`GatewayTransportRegistryTest` —— all / active / byName + isEnabled 过滤（3 cases）
- [x] 7.2 单元：`GracefulShutdownManagerTest` —— 多 transport stopAccepting/stop 顺序 + 单实例抛错不影响他者（3 cases）
- [x] 7.3 单元：`NoopGatewayTransportTest` 自身行为（state 状态机 + 异常注入；2 cases）
- [ ] 7.4 单元：`NettyWebSocketServer.start()` idempotent 验证（**PENDING**：需要 mock ServerBootstrap 或重型集成；当前依赖目视审查 + 状态机断言）

## 8. OpenSpec 校验

- [x] 8.1 `openspec validate add-multi-transport-abstraction --strict`（前置：本次会话已在 spec 完成时通过）

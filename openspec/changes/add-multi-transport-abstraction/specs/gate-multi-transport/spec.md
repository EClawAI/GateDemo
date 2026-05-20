# gate-multi-transport

## ADDED Requirements

### Requirement: 统一 GatewayTransport 接口

The gateway SHALL expose a `GatewayTransport` interface in package `com.clawai.gatedemo.gate.transport` with the following methods:

- `String name()` — unique transport identifier (e.g., `"websocket"`, `"tcp"`, `"kcp"`);
- `boolean isEnabled()` — config-driven on/off switch;
- `void start()` — start accepting connections; SHALL be idempotent;
- `void stopAccepting()` — stop accepting new connections, keep existing channels;
- `void stop()` — shut down underlying resources (event loops, threads);
- `TransportInfo info()` — record reporting host / port / scheme for cluster registration & health probes.

Each Spring-managed transport bean (`NettyWebSocketServer`, `NettyTcpServer`, future `KcpGatewayTransport`, …) SHALL implement this interface. Existing pipeline construction, business handlers, encoder/decoder code SHALL NOT be modified by this change.

#### Scenario: WebSocket server implements GatewayTransport

- **GIVEN** the gate is running with default configuration
- **WHEN** the application context contains `NettyWebSocketServer`
- **THEN** `applicationContext.getBean(NettyWebSocketServer.class)` is assignable to `GatewayTransport`, `name() == "websocket"`, `isEnabled() == true`, and `info().scheme()` is `"ws"` (or `"wss"` when `gate.tls.enabled=true`).

#### Scenario: TCP server implements GatewayTransport when enabled

- **GIVEN** `gate.tcp.enabled=true`
- **WHEN** the application context loads
- **THEN** `NettyTcpServer` is registered as a `GatewayTransport` bean with `name() == "tcp"`, `isEnabled() == true`, and `info().port() == gate.tcp.port`.

#### Scenario: TCP not registered when disabled

- **GIVEN** `gate.tcp.enabled=false` (default)
- **WHEN** the application context loads
- **THEN** no `GatewayTransport` bean of name `"tcp"` is present.

### Requirement: GatewayTransportRegistry 提供统一查询

The gateway SHALL provide a `GatewayTransportRegistry @Component` injecting `List<GatewayTransport>` and exposing:

- `List<GatewayTransport> all()`
- `List<GatewayTransport> active()` — returns only entries with `isEnabled()==true`
- `Optional<GatewayTransport> byName(String name)`

The registry SHALL NOT mutate registered transports' state; it is a read-only view.

#### Scenario: registry only returns enabled transports

- **GIVEN** two beans implementing `GatewayTransport`, one with `isEnabled()=true`, one with `isEnabled()=false`
- **WHEN** `registry.active()` is invoked
- **THEN** the returned list contains exactly the enabled bean.

#### Scenario: byName lookup

- **GIVEN** registry with `[websocket, tcp]`
- **WHEN** `registry.byName("tcp")` is invoked
- **THEN** returns `Optional.of(<tcp transport>)`; `registry.byName("kcp")` returns `Optional.empty()`.

### Requirement: GracefulShutdownManager 遍历所有 transport

The gateway SHALL refactor `GracefulShutdownManager.shutdown()` to:

1. Invoke `stopAccepting()` on every `GatewayTransport` returned by `GatewayTransportRegistry.active()`;
2. Drain attached players via `PlayerService` (existing behavior);
3. Invoke `stop()` on every `GatewayTransport` returned by `registry.active()`.

`GracefulShutdownManager` SHALL NOT import or reference `NettyWebSocketServer` / `NettyTcpServer` concrete classes after this change.

#### Scenario: shutdown stops every registered transport

- **GIVEN** registry contains `websocket` + `tcp` + a `NoopGatewayTransport("kcp")`
- **WHEN** `shutdown()` is invoked
- **THEN** each transport's `stopAccepting()` is called exactly once before drain, and each `stop()` is called exactly once after drain.

#### Scenario: shutdown does not crash on a single transport failure

- **GIVEN** one transport throws on `stopAccepting()`
- **WHEN** `shutdown()` runs
- **THEN** the exception is logged but other transports' `stopAccepting()` and `stop()` still execute.

### Requirement: GateClusterManager 写入 transports 元数据

The gateway SHALL extend Redis cluster registration to include a `transports` field (JSON array) listing every active transport's info; old fields `port` and (optionally) `tcpPort` SHALL remain populated for at least one release for backward compatibility.

#### Scenario: registration JSON contains transports array

- **GIVEN** active transports `[websocket(8888,ws), tcp(9999,tcp)]`
- **WHEN** `GateClusterManager.register` writes the Redis hash
- **THEN** the hash key `transports` contains a JSON array `[{"name":"websocket","host":"0.0.0.0","port":8888,"scheme":"ws"},{"name":"tcp","host":"0.0.0.0","port":9999,"scheme":"tcp"}]`, and legacy keys `port=8888` + `tcpPort=9999` remain.

### Requirement: 第三方 transport 扩展点

The gateway SHALL allow third-party transports (e.g., KCP, QUIC, gRPC streaming) to be registered by:

1. Implementing `GatewayTransport`;
2. Providing the implementation as a Spring bean (typically `@Component` with `@ConditionalOnProperty`);
3. Without modifying any code in `com.clawai.gatedemo.gate.flow`, `com.clawai.gatedemo.gate.service`, `com.clawai.gatedemo.gate.lifecycle`, or `com.clawai.gatedemo.gate.cluster`.

#### Scenario: NoopGatewayTransport plugs into registry without code changes elsewhere

- **GIVEN** a test bean `NoopGatewayTransport("test")` registered via Spring configuration
- **WHEN** the application context loads
- **THEN** `GatewayTransportRegistry.byName("test")` resolves to the noop transport, and `GracefulShutdownManager.shutdown()` invokes its `stopAccepting()` + `stop()` without any code changes in lifecycle or cluster classes.

### Requirement: 启动 / 关停可观测性

The gateway SHALL emit:

- Metric `gate_transport_state{name,state}` — gauge with value `1` when the transport is in the labeled state (one active value per name);
- Counter `gate_transport_start_total{name}` and `gate_transport_stop_total{name}`;
- Log event `gate.transports.ready` listing each active transport's name / host / port / scheme after startup completes.

#### Scenario: startup log lists transports

- **GIVEN** application starts with WS + TCP enabled
- **WHEN** all transports' `start()` returns successfully
- **THEN** a log entry containing `event=gate.transports.ready` and JSON-serialized list of `[{name:"websocket",...},{name:"tcp",...}]` is emitted exactly once.

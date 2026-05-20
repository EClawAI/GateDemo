# Design: B4 — Multi-Transport 抽象

## 1. 背景与目标

### 1.1 现状速写

| 模块 | 当前形态 | 问题 |
|------|----------|------|
| WebSocket server | `NettyWebSocketServer @Component`，`@PostConstruct` 中启动 ServerBootstrap | 唯一被 graceful shutdown 引用 |
| TCP server | `NettyTcpServer @ConditionalOnProperty(gate.tcp.enabled)` | graceful shutdown 漏掉，handler 不是同一份 |
| `GracefulShutdownManager` | 硬编码 `nettyWebSocketServer.stopAccepting()` | 加新 transport 必须改这里 |
| `GateClusterManager` | Redis hash 写 `port` + 可选 `tcpPort` | 加 transport 字段会无限膨胀 |
| `MessageDispatcher` (TCP) | 桩 handler，无 JWT / FlowSession | TCP 不可用于生产 |

### 1.2 B4 目标

| 目标 | 衡量指标 |
|------|----------|
| 引入统一 `GatewayTransport` 接口 | 现有 WS / TCP 实例皆能被 `List<GatewayTransport>` 注入 |
| 关停遍历所有 transport | `GracefulShutdownManager` 不再 import `NettyWebSocketServer` |
| 集群注册元数据可扩展 | Redis hash 写入 `transports` JSON 数组字段 |
| 不破坏现有生产 WS 链路 | WS 端口、TLS、pipeline、handler 全不变 |
| 不引入外部依赖 | 不依赖 KCP / QUIC 库 |
| 暴露扩展点 | 第三方实现 `GatewayTransport`，无需改 gate-service 其他代码 |

### 1.3 显式不做（保留为后续 change）

- 不抽取 codec（`WebSocketBinaryEncoder` / `GameMessageEncoder` 保持独立）；
- 不把 TCP pipeline 切到 `GateNettyWebSocketHandler`（需另设计 codec 复用 + JWT 注入）；
- 不引入 KCP / QUIC 真实实现；
- 不重命名 `GateNettyWebSocketHandler`。

## 2. 接口设计

### 2.1 `GatewayTransport` 接口

```java
package com.clawai.gatedemo.gate.transport;

public interface GatewayTransport {
    String name();                       // 唯一标识，如 "websocket" / "tcp" / "kcp"
    boolean isEnabled();                 // 受配置开关；off 时 Registry 不视为 active
    void start() throws Exception;       // 启动 acceptor (idempotent)
    void stopAccepting();                // 关闭 listening socket，不动已有 channel
    void stop() throws InterruptedException;  // 关 event loop / 释放资源
    TransportInfo info();                // 对外报告

    /** 是否处于 accept 状态；用于 metrics 与 cluster registration。 */
    default TransportState state() { return TransportState.UNKNOWN; }
}

public enum TransportState { STARTING, RUNNING, DRAINING, STOPPED, UNKNOWN }

public record TransportInfo(String name, String host, int port, String scheme) {}
```

- **idempotent `start()`**：若已 running 则 no-op；现有 `NettyWebSocketServer.start()` 已经在 `@PostConstruct` 里执行，B4 仅追加接口；
- **`stopAccepting()` vs `stop()`**：前者用于 graceful shutdown 的「停 accept→drain→stop」三段；现有 `NettyWebSocketServer.stopAccepting()` 已实现；
- **`info()`**：driver 用于 cluster registration 与 `/health`；scheme 取 ws/wss/tcp/tls-tcp/kcp/...

### 2.2 `GatewayTransportRegistry`

```java
@Component
public class GatewayTransportRegistry {
    private final List<GatewayTransport> all;

    public GatewayTransportRegistry(List<GatewayTransport> all) { this.all = List.copyOf(all); }

    public List<GatewayTransport> all() { return all; }
    public List<GatewayTransport> active() {
        return all.stream().filter(GatewayTransport::isEnabled).toList();
    }
    public Optional<GatewayTransport> byName(String name) {
        return all.stream().filter(t -> Objects.equals(t.name(), name)).findFirst();
    }
}
```

- Spring 自动注入所有 `GatewayTransport` bean；
- 顺序由 `@Order` 或 bean 注册顺序决定（影响 graceful shutdown 的 stop 顺序，但不影响正确性）。

## 3. WS / TCP 适配

### 3.1 `NettyWebSocketServer` 实现 `GatewayTransport`

```java
@Component
public class NettyWebSocketServer implements GatewayTransport {
    // 现有字段不动

    @Override public String name() { return "websocket"; }

    @Override public boolean isEnabled() {
        // 当前 WS 总是 enabled（核心服务）
        return true;
    }

    @Override
    public void start() {  // 已存在的 @PostConstruct 初始化
        if (running) return;  // idempotent
        // ... 现有 bootstrap 代码 ...
    }

    @Override public void stopAccepting() { /* 现有方法 */ }
    @Override public void stop() throws InterruptedException { /* 现有 @PreDestroy 内容 */ }

    @Override public TransportInfo info() {
        String scheme = gateConfig.getTls().isEnabled() ? "wss" : "ws";
        return new TransportInfo(name(), gateConfig.getHost(), gateConfig.getPort(), scheme);
    }
}
```

要点：
- **`@PostConstruct` 不动**：方法体仍是 start() 的内容，但额外为 start() 增加 idempotent guard，避免 Spring 容器 init 期间双重启动；
- **测试钩子**：`@PostConstruct` 可改成调用 `start()`，便于测试中不依赖 Spring 单独启动。

### 3.2 `NettyTcpServer` 实现 `GatewayTransport`

```java
@Component
@ConditionalOnProperty(name = "gate.tcp.enabled", havingValue = "true")
public class NettyTcpServer implements GatewayTransport {
    @Override public String name() { return "tcp"; }
    @Override public boolean isEnabled() { return gateConfig.getTcp().isEnabled(); }
    @Override public TransportInfo info() {
        String scheme = "tcp"; // B4 不接 TLS-on-TCP
        return new TransportInfo(name(), gateConfig.getHost(), gateConfig.getTcp().getPort(), scheme);
    }
    // start / stopAccepting / stop 来自现有方法
}
```

- 保留 `@ConditionalOnProperty`：未启用时根本不被 Spring 装配，自然不会出现在 registry；
- `isEnabled()` 仍然显式返回（防御性）。

### 3.3 GracefulShutdownManager 改造

```java
private final GatewayTransportRegistry transports;   // 替代 NettyWebSocketServer

public void shutdown() {
    // Phase 1：stop accept on all transports
    transports.active().forEach(t -> safe(() -> t.stopAccepting()));

    // Phase 2：drain（沿用现有逻辑）
    drainPlayers();

    // Phase 3：stop all transports
    for (GatewayTransport t : transports.active()) {
        safe(() -> t.stop());
    }
}
```

- 单一职责：shutdown 只与 transport registry / player service 协作；
- 顺序：WebSocket / TCP / KCP 之间互不依赖，按 registry 顺序停即可；

### 3.4 GateClusterManager 注册

```java
Map<String, Object> hash = new HashMap<>();
hash.put("host", gateConfig.getHost());
hash.put("port", gateConfig.getPort());                                 // 兼容老 consumer
if (gateConfig.getTcp().isEnabled()) hash.put("tcpPort", gateConfig.getTcp().getPort());
hash.put("transports", transportsAsJson(registry.active()));            // 新字段
```

- `transports` JSON 数组结构：`[{"name":"websocket","host":"0.0.0.0","port":8888,"scheme":"ws"}, ...]`；
- 老字段 `port` / `tcpPort` 保留若干版本，给老消费者迁移窗口。

## 4. 扩展点 / KCP / QUIC

### 4.1 新增第三方 transport 的步骤

1. 新建包 `gate/transport/kcp/`；
2. 实现 `GatewayTransport`：
   - `name()="kcp"`；
   - `start()`：自启动 KCP server（不依赖 Netty event loop 也行）；
   - `stopAccepting()`：关 acceptor；
   - `stop()`：释放资源；
   - `info()`：UDP host:port + `scheme="kcp"`；
3. 添加配置 `gate.transports.kcp.enabled=true` 与对应 `@ConditionalOnProperty`；
4. **不需要修改任何 gate-service 现有代码** —— Spring 自动注入 registry，GracefulShutdown / GateClusterManager 自动覆盖；
5. 业务 handler：直接复用 `GateNettyWebSocketHandler` bean（需 codec 适配，由该 transport 自己提供 KCP→`WrappedMessage` 编解码）。

### 4.2 Test 示例：`NoopGatewayTransport`

```java
class NoopGatewayTransport implements GatewayTransport {
    private final String name;
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.STOPPED);
    NoopGatewayTransport(String name) { this.name = name; }
    @Override public String name() { return name; }
    @Override public boolean isEnabled() { return true; }
    @Override public void start() { state.set(TransportState.RUNNING); }
    @Override public void stopAccepting() { state.set(TransportState.DRAINING); }
    @Override public void stop() { state.set(TransportState.STOPPED); }
    @Override public TransportInfo info() { return new TransportInfo(name, "127.0.0.1", 0, "noop"); }
    @Override public TransportState state() { return state.get(); }
}
```

仅供测试 `Registry` / `GracefulShutdownManager` / metrics 装配，不在 main 代码中。

## 5. 可观测性

- **Metrics**：
  - `gate_transport_state{name=<name>,state=<state>}` —— gauge，1 if matches；
  - `gate_transport_start_total{name}` / `gate_transport_stop_total{name}` —— counter；
- **Startup log**：启动完成时打印 `event=gate.transports.ready transports=[{name=...,host=...,port=...,scheme=...}, ...]`；
- **/health**：透传 transports 列表（后续 change）。

## 6. 权衡 / 决策

### 6.1 为什么不直接抽 codec？

| 方案 | 收益 | 风险 |
|------|------|------|
| B4 仅做 transport interface | 风险低；现有 pipeline 不动 | TCP 接入仍不可用（要等后续 change） |
| B4 同时抽 codec / handler | 一次性收编 | 影响 8 处 encoder/decoder + handler；BinaryWebSocketFrame 与 ByteBuf 边界要小心 |

**选择 B4 仅做 transport 抽象**，把 codec / handler 复用留到后续单独 change。

### 6.2 为什么 graceful shutdown 顺序无关？

各 transport 关停互相独立，不存在依赖；按 registry 顺序串行执行即可。如果未来引入 transport 间依赖（如 control-plane / data-plane），再用 `@Order` 显式排序。

### 6.3 为什么 cluster registration 同时保留 `port` / `tcpPort`？

兼容已部署消费者；后续 change（如 client SDK 升级）可移除。本 change 加 `transports` 字段是「加法」，不破坏。

## 7. 测试矩阵

| ID | 场景 | 验证点 |
|----|------|--------|
| T1 | 仅 WS 启用 | registry.active() = [websocket]，graceful shutdown 关 1 个 |
| T2 | WS + TCP 启用 | registry.active() = [websocket, tcp]，graceful shutdown 关 2 个 |
| T3 | TCP 禁用 | registry.active() 不含 tcp |
| T4 | 第三方 NoopGatewayTransport | 自动被装入 registry |
| T5 | stopAccepting 之后 start 再调一次 | idempotent 不应抛 |
| T6 | info().scheme | wss / ws 根据 TLS 切换 |

## 8. 与其他 changes 的关系

| Change | 关系 |
|--------|------|
| `add-netun-resume-flow-session` | 无依赖：transport 抽象与 flow 解耦 |
| `add-flow-downstream-buffer` | 无依赖：B1 在 handler 层，transport 抽象在 server bootstrap 层 |
| `add-cross-instance-flow-takeover` | 无依赖 |
| `refine-flow-offline-merge-policy` | 无依赖 |
| **后续 change**：transport-codec-unify | B4 是它的前置条件（确认接口形态后再合并 codec） |

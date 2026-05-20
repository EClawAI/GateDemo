# GateDemo Transport Extension Guide

> 对应 OpenSpec change: `add-multi-transport-abstraction`（B4）。
> 适用对象：希望为 GateDemo 接入新的传输协议（KCP / QUIC / gRPC streaming / 自研 UDP 协议等）的开发者。

## 1. 基本概念

`com.clawai.gatedemo.gate.transport` 包下提供三个核心抽象：

| 类型 | 职责 |
| --- | --- |
| `GatewayTransport` | 单个传输协议的接入实现（启动 / 停止 / 状态 / 信息） |
| `TransportInfo` | 描述 transport 对外暴露的 host / port / scheme |
| `TransportState` | 生命周期状态机：`NEW → STARTING → RUNNING → STOPPING_ACCEPT → STOPPING → STOPPED` |
| `GatewayTransportRegistry` | Spring `@Component`，聚合所有 `GatewayTransport` bean，提供 `all/active/byName` 查询 |

业务核心代码（`flow`、`service`、`handler`、`cluster`、`lifecycle`）只通过 `GatewayTransportRegistry`
间接访问 transport，不依赖任何具体 transport 实现。

## 2. 接入步骤（以 KCP 为例）

### 2.1 新建实现类

在新模块或 `gate-service` 子包中提供一个 Spring bean：

```java
@Component
@ConditionalOnProperty(name = "gate.kcp.enabled", havingValue = "true")
public class KcpGatewayTransport implements GatewayTransport {

    private final GateConfig gateConfig;
    private final AtomicReference<TransportState> state =
            new AtomicReference<>(TransportState.NEW);

    public KcpGatewayTransport(GateConfig gateConfig /* + 业务依赖 */) {
        this.gateConfig = gateConfig;
    }

    @Override public String name() { return "kcp"; }
    @Override public boolean isEnabled() { return /* read gate.kcp.enabled */; }
    @Override public TransportState state() { return state.get(); }
    @Override public TransportInfo info() {
        return new TransportInfo("kcp", gateConfig.getHost(), /* port */, "kcp");
    }

    @PostConstruct
    @Override public void start() {
        if (!state.compareAndSet(TransportState.NEW, TransportState.STARTING)) return;
        // ... 启动你的 KCP server，绑定端口
        state.set(TransportState.RUNNING);
    }

    @Override public void stopAccepting() {
        if (state.compareAndSet(TransportState.RUNNING, TransportState.STOPPING_ACCEPT)) {
            // 关闭监听 socket，但保留已建立连接
        }
    }

    @PreDestroy
    @Override public void stop() {
        TransportState prev = state.getAndSet(TransportState.STOPPING);
        if (prev == TransportState.STOPPED) return;
        // ... 释放资源
        state.set(TransportState.STOPPED);
    }
}
```

### 2.2 复用业务 pipeline

新协议的入站消息需要适配到现有的 `WrappedMessage` 表达方式，再走 `FlowSessionManager` /
`PlayerService` 等业务层。可参照：

- `gate.ws.codec.WebSocketBinaryDecoder/Encoder` —— 帧 ↔ `WrappedMessage` 的编解码；
- `gate.tcp.codec.TcpChannelInitializer` —— pipeline 拼装；
- `gate.handler.GateNettyWebSocketHandler` / `gate.tcp.TcpMessageHandler` —— 业务入站；

### 2.3 自动注册

只要 bean 是 `GatewayTransport` 实现，启动时 `GatewayTransportRegistry` 会自动收集；
随后：

- `GracefulShutdownManager` 在关闭时遍历 `registry.active()` 调用 `stopAccepting()` → drain → `stop()`；
- `GateClusterManager` 在 Redis `gate:cluster:{gateId}` 的 `transports` 字段中写入 `[..., {"name":"kcp",...}]`；
- 启动完成时打印一次 `event=gate.transports.ready transports=[...]` 日志。

**不需要修改** `lifecycle` / `cluster` / `flow` / `service` 任何代码。

## 3. 注意事项

1. **幂等**：`start()` 与 `stop()` 都应当对重复调用 no-op；`GracefulShutdownManager` 与 Spring 的
   `@PreDestroy` 可能各自调用一次 `stop()`。
2. **多端口冲突**：所有 transport 共用 `gate.host`，端口需各自避免冲突；建议每个 transport 在自己的
   `*Config` 子段中定义端口。
3. **可观测**：建议在新 transport 中调用相同的 metric 命名前缀
   （如 `gate_transport_state{name="kcp"}`），保持监控一致。
4. **集群一致性**：`transports` JSON 字段是发现侧契约，请保持 `name/host/port/scheme` 四元组完整；
   旧字段 `port` / `tcpPort` 仅为兼容保留，新协议无需写入。
5. **优雅关闭顺序**：`stopAccepting()` 必须立即可见（关闭监听 Socket），否则在 drain 期内会有新连接被
   接受并卡住 shutdown 流程。

## 4. 相关文件

- `gate-service/src/main/java/com/clawai/gatedemo/gate/transport/` —— 抽象层
- `gate-service/src/main/java/com/clawai/gatedemo/gate/ws/NettyWebSocketServer.java` —— WS 实现样板
- `gate-service/src/main/java/com/clawai/gatedemo/gate/tcp/NettyTcpServer.java` —— TCP 实现样板
- `gate-service/src/main/java/com/clawai/gatedemo/gate/lifecycle/GracefulShutdownManager.java`
- `gate-service/src/main/java/com/clawai/gatedemo/gate/cluster/GateClusterManager.java`
- `openspec/changes/add-multi-transport-abstraction/` —— 设计与 spec

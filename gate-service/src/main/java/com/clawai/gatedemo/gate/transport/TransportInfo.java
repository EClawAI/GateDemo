package com.clawai.gatedemo.gate.transport;

/**
 * 描述一个 {@link GatewayTransport} 当前的外部可见信息：
 * <ul>
 *   <li>{@link #name()} —— 与 {@link GatewayTransport#name()} 一致的标识；</li>
 *   <li>{@link #host()} —— 监听的 host（典型为 {@code 0.0.0.0}）；</li>
 *   <li>{@link #port()} —— 监听端口；</li>
 *   <li>{@link #scheme()} —— 业务用的 URI scheme（如 {@code ws/wss/tcp/kcp}）。</li>
 * </ul>
 *
 * <p>用于：集群注册、健康/调试端点、metric label。
 *
 * <p>对应 {@code openspec/changes/add-multi-transport-abstraction/specs/gate-multi-transport/spec.md}
 * 「统一 GatewayTransport 接口」与「GateClusterManager 写入 transports 元数据」两个 Requirement。
 */
public record TransportInfo(String name, String host, int port, String scheme) {
}

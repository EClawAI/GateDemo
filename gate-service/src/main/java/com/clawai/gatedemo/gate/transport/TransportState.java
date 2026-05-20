package com.clawai.gatedemo.gate.transport;

/**
 * {@link GatewayTransport} 生命周期状态机：
 * <pre>
 *   NEW → STARTING → RUNNING → STOPPING_ACCEPT → STOPPING → STOPPED
 *                              ↘ (stopAccepting 跳过) ↗
 * </pre>
 * <ul>
 *   <li>{@code NEW}: bean 构造但尚未 {@link GatewayTransport#start()}.</li>
 *   <li>{@code STARTING}: 正在初始化资源 (EventLoop, bind)。</li>
 *   <li>{@code RUNNING}: 已 bind，可接受新连接。</li>
 *   <li>{@code STOPPING_ACCEPT}: {@link GatewayTransport#stopAccepting()} 已调用，服务端 Channel 已关闭，
 *       但保留已存在的连接以等待业务 drain。</li>
 *   <li>{@code STOPPING}: 正在释放线程组/资源。</li>
 *   <li>{@code STOPPED}: 完全释放。</li>
 * </ul>
 */
public enum TransportState {
    NEW,
    STARTING,
    RUNNING,
    STOPPING_ACCEPT,
    STOPPING,
    STOPPED
}

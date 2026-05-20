package com.clawai.gatedemo.gate.transport;

/**
 * 统一的网关接入层抽象（B4：multi-transport abstraction）。
 *
 * <p>所有具体 transport（WebSocket / TCP / 未来 KCP/QUIC/gRPC streaming）实现该接口，
 * 由 {@link GatewayTransportRegistry} 收集并供 lifecycle / cluster / 可观测性使用。
 *
 * <p>核心契约：
 * <ul>
 *   <li>{@link #start()} 与 {@link #stop()} 应当幂等：重复调用不应抛错；</li>
 *   <li>{@link #stopAccepting()} 仅关闭服务端监听 Channel，不影响已有连接 → 用于优雅关闭阶段一；</li>
 *   <li>{@link #info()} 在任意状态下都应可调用并返回有效快照（host/port/scheme 在 {@link #start()} 前可为占位）；</li>
 *   <li>{@link #state()} 反映当前状态机位置。</li>
 * </ul>
 *
 * <p>对应 spec：{@code openspec/changes/add-multi-transport-abstraction/specs/gate-multi-transport/spec.md}
 * 「统一 GatewayTransport 接口」与「第三方 transport 扩展点」。
 */
public interface GatewayTransport {

    /**
     * 唯一标识，例如 {@code "websocket"} / {@code "tcp"} / {@code "kcp"}。
     * <p>必须稳定，可作为 metric label 与 registry key。
     */
    String name();

    /**
     * 是否启用：由配置（如 {@code gate.tcp.enabled=true}）或 {@code @ConditionalOnProperty} 控制。
     * <p>对于未启用的 transport，{@link GatewayTransportRegistry#active()} 将其过滤掉。
     */
    boolean isEnabled();

    /**
     * 启动 transport（bind / 启动 EventLoop / 注册 metrics 等）。
     * <p>幂等：若已经 {@link TransportState#RUNNING} 应直接返回。
     */
    void start() throws Exception;

    /**
     * 停止接受新连接（优雅关闭阶段一）。
     * <p>关闭服务端监听 Channel，但保留已建立的连接，以便业务层 drain。
     */
    void stopAccepting();

    /**
     * 完全停止：释放 EventLoop、关闭剩余 Channel、清理资源。
     * <p>幂等：若已经 {@link TransportState#STOPPED} 应直接返回。
     */
    void stop();

    /**
     * 当前 transport 的对外信息快照（用于集群注册、调试端点、健康检查）。
     */
    TransportInfo info();

    /**
     * 当前生命周期状态。
     */
    TransportState state();
}

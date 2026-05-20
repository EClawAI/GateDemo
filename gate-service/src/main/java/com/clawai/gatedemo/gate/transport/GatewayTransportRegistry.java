package com.clawai.gatedemo.gate.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 收集所有 {@link GatewayTransport} bean，供 lifecycle / cluster / 可观测性统一查询。
 *
 * <p>只读视图：不会修改 transport 状态，仅暴露 {@link #all()}、{@link #active()}、{@link #byName(String)}。
 *
 * <p>对应 spec：{@code openspec/changes/add-multi-transport-abstraction/specs/gate-multi-transport/spec.md}
 * 「GatewayTransportRegistry 提供统一查询」与「启动 / 关停可观测性」。
 */
@Component
public class GatewayTransportRegistry {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTransportRegistry.class);

    private final List<GatewayTransport> transports;

    /**
     * @param provider Spring 的 lazy 注入，避免对具体 bean 的硬依赖；首次访问时即按当前应用上下文快照。
     */
    public GatewayTransportRegistry(ObjectProvider<GatewayTransport> provider) {
        this.transports = Collections.unmodifiableList(provider.stream().toList());
    }

    /** @return 所有 transport，包括 disabled 的。 */
    public List<GatewayTransport> all() {
        return transports;
    }

    /** @return 仅 {@link GatewayTransport#isEnabled()} 为 true 的 transport。 */
    public List<GatewayTransport> active() {
        return transports.stream().filter(GatewayTransport::isEnabled).toList();
    }

    /** @return 按 {@link GatewayTransport#name()} 精确匹配。 */
    public Optional<GatewayTransport> byName(String name) {
        if (name == null) return Optional.empty();
        return transports.stream().filter(t -> name.equals(t.name())).findFirst();
    }

    /**
     * 启动完成后打印一次 {@code event=gate.transports.ready} 日志（spec 「启动 / 关停可观测性」）。
     * <p>使用 {@link ApplicationReadyEvent} 以保证所有 {@code @PostConstruct} 阶段的 transport 都已 bind。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void logReady() {
        StringBuilder sb = new StringBuilder();
        sb.append("event=gate.transports.ready transports=[");
        boolean first = true;
        for (GatewayTransport t : active()) {
            if (!first) sb.append(',');
            first = false;
            TransportInfo i = t.info();
            sb.append('{')
                    .append("name=").append(i.name())
                    .append(",host=").append(i.host())
                    .append(",port=").append(i.port())
                    .append(",scheme=").append(i.scheme())
                    .append(",state=").append(t.state())
                    .append('}');
        }
        sb.append(']');
        logger.info(sb.toString());
    }
}

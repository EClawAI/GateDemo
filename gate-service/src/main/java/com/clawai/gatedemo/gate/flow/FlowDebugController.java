package com.clawai.gatedemo.gate.flow;

import com.clawai.gatedemo.gate.service.OfflineMessageService;
import io.netty.channel.Channel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 仅在 {@code dev} profile 下生效的内省端点：返回当前 gate 实例内的 FlowSession 列表，便于本地诊断
 * 「为什么 RESUME 走 NEW 降级」「为什么某玩家 DETACHED 一直不销毁」等问题。
 *
 * <p>生产环境严禁开启：曝露 playerId / flowId / Channel 状态等敏感信息。
 */
@RestController
@RequestMapping("/debug/flows")
@Profile("dev")
public class FlowDebugController {

    private final FlowSessionManager manager;
    private final OfflineMessageService offlineService;

    public FlowDebugController(FlowSessionManager manager,
                               @Autowired(required = false) OfflineMessageService offlineService) {
        this.manager = manager;
        this.offlineService = offlineService;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<Map<String, Object>> flows = manager.snapshot().stream()
                .map(this::describe)
                .collect(Collectors.toList());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("total", flows.size());
        body.put("attached", flows.stream().filter(m -> "ATTACHED".equals(m.get("state"))).count());
        body.put("flows", flows);
        return body;
    }

    private Map<String, Object> describe(FlowSession session) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flowId", session.getFlowId());
        m.put("playerId", session.getPlayerId());
        m.put("gameId", session.getGameId());
        m.put("ownerGateId", session.getOwnerGateId());
        m.put("state", session.getState().name());
        m.put("createdAt", session.getCreatedAt());
        m.put("expiresAt", session.getExpiresAt());
        m.put("detachedAt", session.getDetachedAt());
        m.put("lastSeqAnchor", session.getLastSeqAnchor());
        m.put("features", String.format("0x%x", session.getFeatures()));
        m.put("nextGwSeq", session.peekNextGwSeq());
        DownstreamBuffer buf = session.getBuffer();
        if (buf != null) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("size", buf.size());
            b.put("totalBytes", buf.totalBytes());
            b.put("minGwSeq", buf.minGwSeq());
            b.put("maxGwSeq", buf.maxGwSeq());
            b.put("capacityEntries", buf.capacityEntries());
            b.put("capacityBytes", buf.capacityBytes());
            b.put("overflowPolicy", buf.overflowPolicy().name());
            m.put("buffer", b);
        } else {
            m.put("buffer", null);
        }
        Channel ch = session.getCurrentChannel();
        m.put("channelActive", ch != null && ch.isActive());
        if (offlineService != null) {
            try {
                m.put("offlineCount", offlineService.offlineFlowCount(session.getFlowId()));
            } catch (Exception ignore) {
                m.put("offlineCount", 0L);
            }
        } else {
            m.put("offlineCount", 0L);
        }
        return m;
    }
}

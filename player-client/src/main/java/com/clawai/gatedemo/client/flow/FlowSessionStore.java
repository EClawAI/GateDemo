package com.clawai.gatedemo.client.flow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Demo 客户端持久化 {@code flow_id}（以及最近一次下行 seq 锚点）的最小实现。
 *
 * <p>策略：在 {@code ${user.home}/.gatedemo} 下写一个文本文件，便于客户端进程重启 / 重新登录时
 * 自动携带上次会话的 {@code flow_id} 走 RESUME 路径。失败一律降级为「无 flowId 走 NEW」，
 * 不影响 demo 主流程。
 *
 * <p>真实客户端应改用 PlayerPrefs / KeyChain / SecureStorage 等更合适的载体；本类 <b>只</b>
 * 服务于 GateDemo player-client。
 */
@Component
public class FlowSessionStore {

    private static final Logger logger = LoggerFactory.getLogger(FlowSessionStore.class);

    private final Path storePath;
    private final AtomicReference<String> flowIdRef = new AtomicReference<>("");
    private final AtomicLong lastClientRecvSeqRef = new AtomicLong(0L);

    public FlowSessionStore(@Value("${player.flow.store-path:}") String configuredPath) {
        Path resolved;
        if (configuredPath == null || configuredPath.isBlank()) {
            resolved = Paths.get(System.getProperty("user.home", "."), ".gatedemo", "flow-session.txt");
        } else {
            resolved = Paths.get(configuredPath);
        }
        this.storePath = resolved;
        load();
    }

    private void load() {
        try {
            if (!Files.exists(storePath)) {
                return;
            }
            String content = Files.readString(storePath, StandardCharsets.UTF_8).trim();
            // 文件格式：第一行 flowId，第二行 lastClientRecvSeq（可缺省）
            String[] lines = content.split("\\r?\\n", -1);
            if (lines.length > 0) {
                flowIdRef.set(lines[0].trim());
            }
            if (lines.length > 1) {
                try {
                    lastClientRecvSeqRef.set(Long.parseLong(lines[1].trim()));
                } catch (NumberFormatException ignored) { /* keep 0 */ }
            }
            logger.info("Loaded flow session: flowId={}, lastClientRecvSeq={}",
                    flowIdRef.get(), lastClientRecvSeqRef.get());
        } catch (IOException e) {
            logger.warn("Failed to load flow session at {}: {}", storePath, e.getMessage());
        }
    }

    public String getFlowId() {
        return flowIdRef.get();
    }

    public long getLastClientRecvSeq() {
        return lastClientRecvSeqRef.get();
    }

    public void update(String flowId, long lastClientRecvSeq) {
        flowIdRef.set(flowId == null ? "" : flowId);
        lastClientRecvSeqRef.set(Math.max(0L, lastClientRecvSeq));
        persist();
    }

    public void clear() {
        flowIdRef.set("");
        lastClientRecvSeqRef.set(0L);
        try {
            Files.deleteIfExists(storePath);
        } catch (IOException e) {
            logger.warn("Failed to delete flow session at {}: {}", storePath, e.getMessage());
        }
    }

    private void persist() {
        try {
            if (storePath.getParent() != null) {
                Files.createDirectories(storePath.getParent());
            }
            String content = flowIdRef.get() + "\n" + lastClientRecvSeqRef.get() + "\n";
            Files.writeString(storePath, content, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.warn("Failed to persist flow session at {}: {}", storePath, e.getMessage());
        }
    }
}

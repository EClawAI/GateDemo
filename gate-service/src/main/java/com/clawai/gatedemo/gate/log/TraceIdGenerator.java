package com.clawai.gatedemo.gate.log;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TraceIdGenerator {

    private static final Logger logger = LoggerFactory.getLogger(TraceIdGenerator.class);

    private static final ThreadLocal<String> CURRENT_TRACE_ID = new ThreadLocal<>();
    private static final ConcurrentHashMap<String, Long> activeTraces = new ConcurrentHashMap<>();

    public static String generate() {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        CURRENT_TRACE_ID.set(traceId);
        activeTraces.put(traceId, System.currentTimeMillis());
        return traceId;
    }

    public static String getCurrent() {
        String traceId = CURRENT_TRACE_ID.get();
        return traceId != null ? traceId : generate();
    }

    public static void setCurrent(String traceId) {
        CURRENT_TRACE_ID.set(traceId);
    }

    public static void clear() {
        CURRENT_TRACE_ID.remove();
    }

    public static String generateForMessage(long playerId, short messageId) {
        return String.format("%d-%d-%s", playerId, messageId, UUID.randomUUID().toString().substring(0, 8));
    }
}

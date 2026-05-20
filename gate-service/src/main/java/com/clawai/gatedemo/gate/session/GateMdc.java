package com.clawai.gatedemo.gate.session;

import org.slf4j.MDC;

/**
 * 对齐 lzwSLG/bsserver/icefire-gate {@code GateMdc}：在共享 EventLoop 线程上为日志打 sid/玩家号/trace。
 */
public final class GateMdc {

    public static final String SESSION_ID = "sid";
    public static final String PLAYER_ID = "pid";
    public static final String TRACE_ID = "tid";

    private GateMdc() {}

    public static void setPlayerContext(String sessionShortId, Long playerId) {
        if (sessionShortId != null) {
            MDC.put(SESSION_ID, sessionShortId);
        }
        if (playerId != null && playerId > 0) {
            MDC.put(PLAYER_ID, String.valueOf(playerId));
        }
    }

    public static void setTraceId(long traceId) {
        MDC.put(TRACE_ID, String.valueOf(traceId));
    }

    public static void clear() {
        MDC.remove(SESSION_ID);
        MDC.remove(PLAYER_ID);
        MDC.remove(TRACE_ID);
    }
}

package com.clawai.gatedemo.client.robot.framework;

/**
 * 解析 e2e 测试运行时的目标环境（gate / login service 地址、TCP 端口、第二 gate 实例等）。
 *
 * <p>统一通过环境变量或 system property 注入，便于 scripts/run-tests.sh 和 CI pipeline 覆盖：
 * <ul>
 *   <li>{@code GATE_E2E_HOST} (default {@code 127.0.0.1})</li>
 *   <li>{@code GATE_E2E_WS_PORT} (default {@code 8888})</li>
 *   <li>{@code GATE_E2E_WS_PORT_2} (default {@code 8889})：S04 跨实例场景用的第二个 gate 实例</li>
 *   <li>{@code GATE_E2E_TCP_PORT} (default {@code 9999})：S06 多 transport 场景用，0 表示禁用 TCP 校验</li>
 *   <li>{@code GATE_E2E_LOGIN_URL} (default {@code http://127.0.0.1:9086})</li>
 *   <li>{@code GATE_E2E_GAME_ID} (default {@code 1001})</li>
 *   <li>{@code GATE_E2E_TIMEOUT_MS} (default {@code 10000})</li>
 *   <li>{@code GATE_E2E_DETACHED_TTL_SECONDS} (default {@code 60})：与 gate 配置保持一致，
 *       S02 DETACHED TTL 用于判断等多久才能验证「过期」</li>
 * </ul>
 *
 * <p>system property 优先于环境变量；环境变量优先于默认值。
 */
public final class RobotEnvironment {

    private final String host;
    private final int wsPort;
    private final int wsPort2;
    private final int tcpPort;
    private final String loginUrl;
    private final int gameId;
    private final long timeoutMs;
    private final int detachedTtlSeconds;

    private RobotEnvironment(String host, int wsPort, int wsPort2, int tcpPort,
                             String loginUrl, int gameId, long timeoutMs, int detachedTtlSeconds) {
        this.host = host;
        this.wsPort = wsPort;
        this.wsPort2 = wsPort2;
        this.tcpPort = tcpPort;
        this.loginUrl = loginUrl;
        this.gameId = gameId;
        this.timeoutMs = timeoutMs;
        this.detachedTtlSeconds = detachedTtlSeconds;
    }

    public static RobotEnvironment fromSystem() {
        return new RobotEnvironment(
                read("GATE_E2E_HOST", "127.0.0.1"),
                Integer.parseInt(read("GATE_E2E_WS_PORT", "8888")),
                Integer.parseInt(read("GATE_E2E_WS_PORT_2", "8889")),
                Integer.parseInt(read("GATE_E2E_TCP_PORT", "9999")),
                read("GATE_E2E_LOGIN_URL", "http://127.0.0.1:9086"),
                Integer.parseInt(read("GATE_E2E_GAME_ID", "1001")),
                Long.parseLong(read("GATE_E2E_TIMEOUT_MS", "10000")),
                Integer.parseInt(read("GATE_E2E_DETACHED_TTL_SECONDS", "60")));
    }

    private static String read(String key, String def) {
        String sys = System.getProperty(key);
        if (sys != null && !sys.isBlank()) return sys;
        String env = System.getenv(key);
        if (env != null && !env.isBlank()) return env;
        return def;
    }

    public String host() { return host; }
    public int wsPort() { return wsPort; }
    public int wsPort2() { return wsPort2; }
    public int tcpPort() { return tcpPort; }
    public String loginUrl() { return loginUrl; }
    public int gameId() { return gameId; }
    public long timeoutMs() { return timeoutMs; }
    public int detachedTtlSeconds() { return detachedTtlSeconds; }

    /**
     * 是否启用 TCP 场景：{@code tcpPort > 0} 视为启用，否则 S06 跳过 TCP assertion 与
     * S06 自身的 robot client 构造。
     */
    public boolean tcpEnabled() {
        return tcpPort > 0;
    }

    @Override
    public String toString() {
        return "RobotEnvironment{host=" + host
                + ", wsPort=" + wsPort
                + ", wsPort2=" + wsPort2
                + ", tcpPort=" + tcpPort
                + ", loginUrl=" + loginUrl
                + ", gameId=" + gameId
                + ", timeoutMs=" + timeoutMs
                + ", detachedTtlSeconds=" + detachedTtlSeconds + '}';
    }
}

package com.clawai.gatedemo.client.robot.framework;

/**
 * 解析 e2e 测试运行时的目标环境（gate / login service 地址等）。
 *
 * <p>统一通过环境变量或 system property 注入，便于 scripts/run-tests.sh 和 CI pipeline 覆盖：
 * <ul>
 *   <li>{@code GATE_E2E_HOST} (default {@code 127.0.0.1})</li>
 *   <li>{@code GATE_E2E_WS_PORT} (default {@code 8888})</li>
 *   <li>{@code GATE_E2E_LOGIN_URL} (default {@code http://127.0.0.1:9086})</li>
 *   <li>{@code GATE_E2E_GAME_ID} (default {@code 1001})</li>
 *   <li>{@code GATE_E2E_TIMEOUT_MS} (default {@code 10000})</li>
 * </ul>
 *
 * <p>system property 优先于环境变量；环境变量优先于默认值。
 */
public final class RobotEnvironment {

    private final String host;
    private final int wsPort;
    private final String loginUrl;
    private final int gameId;
    private final long timeoutMs;

    private RobotEnvironment(String host, int wsPort, String loginUrl, int gameId, long timeoutMs) {
        this.host = host;
        this.wsPort = wsPort;
        this.loginUrl = loginUrl;
        this.gameId = gameId;
        this.timeoutMs = timeoutMs;
    }

    public static RobotEnvironment fromSystem() {
        return new RobotEnvironment(
                read("GATE_E2E_HOST", "127.0.0.1"),
                Integer.parseInt(read("GATE_E2E_WS_PORT", "8888")),
                read("GATE_E2E_LOGIN_URL", "http://127.0.0.1:9086"),
                Integer.parseInt(read("GATE_E2E_GAME_ID", "1001")),
                Long.parseLong(read("GATE_E2E_TIMEOUT_MS", "10000")));
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
    public String loginUrl() { return loginUrl; }
    public int gameId() { return gameId; }
    public long timeoutMs() { return timeoutMs; }

    @Override
    public String toString() {
        return "RobotEnvironment{host=" + host + ", wsPort=" + wsPort
                + ", loginUrl=" + loginUrl + ", gameId=" + gameId
                + ", timeoutMs=" + timeoutMs + '}';
    }
}

package com.clawai.gatedemo.client.robot.framework;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 调 login-service 的 {@code POST /api/v1/login} 获取 JWT 与目标 gate 信息。
 *
 * <p>用 JDK 内置 {@link HttpClient}，不引入额外依赖；解析用项目已有的 Jackson。
 */
public final class LoginClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String loginUrl;

    public LoginClient(String loginUrl) {
        this.loginUrl = loginUrl.endsWith("/") ? loginUrl.substring(0, loginUrl.length() - 1) : loginUrl;
    }

    /**
     * 申请 JWT。
     *
     * @param playerId 玩家 ID
     * @return 解析后的登录响应
     * @throws Exception 网络错误 / HTTP 非 2xx / 响应格式不符
     */
    public LoginResult login(long playerId) throws Exception {
        String body = mapper.writeValueAsString(java.util.Map.of("playerId", playerId));
        HttpRequest req = HttpRequest.newBuilder(URI.create(loginUrl + "/api/v1/login"))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("login http " + resp.statusCode() + ": " + resp.body());
        }
        JsonNode root = mapper.readTree(resp.body());
        int code = root.path("code").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("login api error code=" + code
                    + " msg=" + root.path("message").asText());
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) {
            throw new IllegalStateException("login response missing data: " + resp.body());
        }
        return new LoginResult(
                data.path("token").asText(),
                data.path("gameId").asInt(),
                data.path("gate").path("id").asText(),
                data.path("gate").path("host").asText(),
                data.path("gate").path("port").asInt());
    }

    public record LoginResult(String token, int gameId, String gateId, String gateHost, int gatePort) {}
}

package com.clawai.gatedemo.login.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 校验请求头 {@code X-API-Key}；健康检查与 Actuator 路径放行；未配置密钥时不启用校验。
 */
@Component
@Order(1)
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ApiKeyFilter.class);

    private static final String HEADER_X_API_KEY = "X-API-Key";

    /** 与网关/调用方约定的静态 API 密钥，空串表示关闭过滤 */
    @Value("${login.security.api-key:}")
    private String configuredApiKey;

    /**
     * 未配置密钥则直接放行；否则除健康路径外必须携带正确 {@code X-API-Key}。
     *
     * @param request     当前请求
     * @param response    校验失败时写 401 与 JSON 错误体
     * @param filterChain 通过后继续过滤器链
     */
    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (configuredApiKey == null || configuredApiKey.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (isHealthEndpoint(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(HEADER_X_API_KEY);
        if (apiKey == null || !configuredApiKey.equals(apiKey)) {
            logger.warn("API Key validation failed: path={}, hasKey={}", path, apiKey != null);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isHealthEndpoint(String path) {
        return "/health".equals(path) || path != null && path.startsWith("/actuator/");
    }
}

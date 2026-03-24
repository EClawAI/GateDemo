package com.clawai.gatedemo.gate.auth;

import io.jsonwebtoken.Claims;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Token 验证器：JWT 验签 + Redis 黑名单检查
 */
@Component
public class TokenValidator {

    private static final Logger logger = LoggerFactory.getLogger(TokenValidator.class);

    private final TokenService tokenService;
    private final TokenBlacklistService blacklistService;

    /**
     * @param tokenService       JWT 验签
     * @param blacklistService   Redis 黑名单（登出/吊销）
     */
    public TokenValidator(TokenService tokenService, TokenBlacklistService blacklistService) {
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
    }

    /**
     * 综合校验：JWT 合法、未进黑名单，且 subject 可解析为玩家 ID。
     * 会访问 Redis（黑名单查询）并解析 JWT，不修改连接或会话状态。
     *
     * @param token Bearer 内完整 token；null 或空白返回 null
     * @return 成功返回玩家 ID；任一环节失败返回 null
     */
    public Long validate(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }

        Claims claims = tokenService.validateToken(token);
        if (claims == null) {
            return null;
        }

        String jti = claims.getId();
        if (jti != null && blacklistService.isBlacklisted(jti)) {
            logger.warn("Token is blacklisted: jti={}", jti);
            return null;
        }

        try {
            return Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            logger.warn("Invalid subject in token: {}", claims.getSubject());
            return null;
        }
    }
}

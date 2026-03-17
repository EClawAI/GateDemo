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

    public TokenValidator(TokenService tokenService, TokenBlacklistService blacklistService) {
        this.tokenService = tokenService;
        this.blacklistService = blacklistService;
    }

    /**
     * 验证 token 是否有效（JWT 验签 + 黑名单检查）
     * @return playerId if valid, null if invalid
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

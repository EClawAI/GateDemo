package com.clawai.gatedemo.gate.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT Token 验签服务（Gate 端只做验签，不签发）
 */
@Component
public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private final SecretKey secretKey;

    public TokenService(@Value("${gate.jwt.secret:DefaultGateDemoSecretKeyForHMACSHA256Auth!}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 验证并解析 JWT token
     * @return Claims if valid, null if invalid
     */
    public Claims validateToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            logger.warn("Token expired: {}", e.getMessage());
        } catch (JwtException e) {
            logger.warn("Invalid token: {}", e.getMessage());
        }
        return null;
    }

}

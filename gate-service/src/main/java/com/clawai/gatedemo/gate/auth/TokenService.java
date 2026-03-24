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

    /** HMAC-SHA256 验签密钥，由配置注入后派生；网关不签发 token。 */
    private final SecretKey secretKey;

    /**
     * 由配置的明文密钥构造验签用 {@link SecretKey}（须满足 JJWT 对密钥长度的要求）。
     *
     * @param secret 对称密钥字符串，须与签发方一致
     */
    public TokenService(@Value("${gate.jwt.secret:DefaultGateDemoSecretKeyForHMACSHA256Auth!}") String secret) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 校验签名与有效期并解析 JWT 载荷；仅验签，不修改 token。
     *
     * @param token 完整 JWT 字符串
     * @return 合法时返回 {@link Claims}；过期或签名校验失败返回 null，并记录 warn 日志
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

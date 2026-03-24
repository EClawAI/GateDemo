package com.clawai.gatedemo.login.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发：由登录服务生成令牌，网关服务使用同一密钥验签。
 */
@Service
public class JwtTokenService {

    /** HMAC 签名用密钥材料 */
    private final SecretKey secretKey;
    /** 令牌有效期（秒） */
    private final long expireSeconds;

    /**
     * @param secret        HMAC 密钥字符串，须与网关配置一致
     * @param expireSeconds 签发令牌的有效时长（秒）
     */
    public JwtTokenService(
            @Value("${login.jwt.secret:DefaultGateDemoSecretKeyForHMACSHA256Auth!}") String secret,
            @Value("${login.jwt.expire-seconds:7200}") long expireSeconds) {
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expireSeconds = expireSeconds;
    }

    public String generateToken(Long playerId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expireSeconds * 1000);

        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(String.valueOf(playerId))
                .issuedAt(now)
                .expiration(expiry)
                .signWith(secretKey)
                .compact();
    }

    /**
     * @return 当前配置的令牌过期秒数，供外部展示或对齐客户端逻辑
     */
    public long getExpireSeconds() {
        return expireSeconds;
    }
}

package com.clawai.gatedemo.gate.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（Redis 存储）
 * 登出或 token 失效时，将 jti 加入黑名单
 */
@Component
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);
    private static final String BLACKLIST_PREFIX = "token:blacklist:";

    private final StringRedisTemplate redisTemplate;

    public TokenBlacklistService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void addToBlacklist(String jti, long ttlSeconds) {
        if (jti == null) return;
        try {
            redisTemplate.opsForValue().set(BLACKLIST_PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
            logger.info("Token jti={} added to blacklist, ttl={}s", jti, ttlSeconds);
        } catch (Exception e) {
            logger.error("Failed to add token to blacklist: {}", e.getMessage());
        }
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) return false;
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            logger.error("Failed to check blacklist: {}", e.getMessage());
            return false;
        }
    }
}

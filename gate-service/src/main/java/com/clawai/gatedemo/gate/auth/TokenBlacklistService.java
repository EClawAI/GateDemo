package com.clawai.gatedemo.gate.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Token 黑名单服务（Redis 存储）
 * 登出或 token 失效时，将 jti 加入黑名单
 */
@Component
public class TokenBlacklistService {

    private static final Logger logger = LoggerFactory.getLogger(TokenBlacklistService.class);

    /** Redis key 前缀，与 JWT jti 拼接后作为黑名单条目键。 */
    private static final String BLACKLIST_PREFIX = "token:blacklist:";

    /** 字符串 KV 操作，用于写入/探测黑名单（通常为 RedisTemplate）。 */
    private final RedisOperations<String, String> redisOperations;

    public TokenBlacklistService(RedisOperations<String, String> redisOperations) {
        this.redisOperations = redisOperations;
    }

    /**
     * 将指定 jti 记入黑名单，在 ttl 内拒绝该 token。
     *
     * @param jti         JWT 唯一标识；为 null 时不做任何操作
     * @param ttlSeconds  存活秒数，建议与 token 剩余有效期对齐，避免 Redis 堆积
     */
    public void addToBlacklist(String jti, long ttlSeconds) {
        if (jti == null) return;
        try {
            redisOperations.opsForValue().set(BLACKLIST_PREFIX + jti, "1", ttlSeconds, TimeUnit.SECONDS);
            logger.info("Token jti={} added to blacklist, ttl={}s", jti, ttlSeconds);
        } catch (Exception e) {
            logger.error("Failed to add token to blacklist: {}", e.getMessage());
        }
    }

    /**
     * 判断 jti 是否已被拉黑。
     *
     * @param jti JWT 唯一标识；null 视为未拉黑
     * @return 存在对应 Redis 键则为 true；Redis 异常时为 false（fail-open，避免误杀全站）
     */
    public boolean isBlacklisted(String jti) {
        if (jti == null) return false;
        try {
            return Boolean.TRUE.equals(redisOperations.hasKey(BLACKLIST_PREFIX + jti));
        } catch (Exception e) {
            logger.error("Failed to check blacklist: {}", e.getMessage());
            return false;
        }
    }
}

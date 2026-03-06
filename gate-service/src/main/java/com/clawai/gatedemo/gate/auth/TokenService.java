package com.clawai.gatedemo.gate.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.Base64;
import java.security.SecureRandom;

/**
 * Token服务 - 生成和管理用户认证Token
 *
 * 设计原理：
 * Token是用户身份的凭证，类似于web应用中的session。
 * 客户端登录后获得Token，后续请求携带Token进行身份验证。
 *
 * Token特点：
 * - 随机生成：使用SecureRandom确保不可预测
 * - 有效期：设置过期时间，过期后需要重新登录
 * - 内存存储：存储在ConcurrentHashMap中，重启后失效
 *
 * 为什么不用JWT？
 * - JWT优点：无状态、可验证
 * - JWT缺点：无法主动失效（只能等过期）
 * - 本项目使用内存Token，可以随时失效
 *
 * 为什么不存Redis？
 * - 简单场景：单机部署，内存足够
 * - 后续可扩展：如果需要多实例，可以改用Redis
 *
 * 工作流程：
 * 1. 客户端登录 → Game服务验证
 * 2. 验证成功 → Gate生成Token，返回给客户端
 * 3. 客户端请求携带Token
 * 4. Gate验证Token有效，放行请求
 * 5. Token过期或失效 → 要求重新登录
 *
 * 清理机制：
 * - 定时清理：每60秒清理过期的Token
 * - 自动清理：验证时如果发现过期则删除
 */
public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    /** Token存储：Token → TokenInfo */
    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();

    /** 随机数生成器：用于生成Token */
    private final SecureRandom secureRandom = new SecureRandom();

    /** Token过期时间（秒） */
    private final long tokenExpireSeconds;

    /** Token清理调度器 */
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-cleaner");
        t.setDaemon(true);
        return t;
    });

    /**
     * 构造函数
     * @param tokenExpireSeconds Token有效期（秒）
     */
    public TokenService(long tokenExpireSeconds) {
        this.tokenExpireSeconds = tokenExpireSeconds;
        startTokenCleaner();
    }

    /**
     * 生成Token
     *
     * 生成流程：
     * 1. 生成32字节随机数
     * 2. Base64 URL编码（无填充）
     * 3. 存入内存Map，设置过期时间
     *
     * 为什么是32字节？
     * - 256位随机数，安全强度高
     * - Base64编码后约43个字符
     *
     * @param playerId 玩家ID
     * @return Token字符串
     */
    public String generateToken(Long playerId) {
        // 生成32字节随机数
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        
        // Base64 URL编码（无填充）
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        // 存入内存，设置过期时间
        TokenInfo info = new TokenInfo(playerId, System.currentTimeMillis() + tokenExpireSeconds * 1000);
        tokens.put(token, info);

        logger.info("Generated token for player: {}", playerId);
        return token;
    }

    /**
     * 验证Token是否有效
     *
     * 验证逻辑：
     * 1. Token是否存在
     2. Token是否过期
     *
     * @param token Token字符串
     * @return true表示有效
     */
    public boolean validateToken(String token) {
        TokenInfo info = tokens.get(token);
        if (info == null) {
            return false;
        }

        // 检查是否过期
        if (System.currentTimeMillis() > info.expireTime) {
            tokens.remove(token);
            logger.info("Token expired for player: {}", info.playerId);
            return false;
        }

        return true;
    }

    /**
     * 根据Token获取玩家ID
     *
     * @param token Token字符串
     * @return 玩家ID，如果Token无效返回null
     */
    public Long getPlayerId(String token) {
        TokenInfo info = tokens.get(token);
        return info != null ? info.playerId : null;
    }

    /**
     * 删除Token（登出时调用）
     *
     * @param token Token字符串
     */
    public void removeToken(String token) {
        tokens.remove(token);
    }

    /**
     * 刷新Token有效期
     *
     * 调用场景：玩家活跃时自动续期
     *
     * @param token Token字符串
     */
    public void refreshToken(String token) {
        TokenInfo info = tokens.get(token);
        if (info != null) {
            // 重置过期时间
            info.expireTime = System.currentTimeMillis() + tokenExpireSeconds * 1000;
        }
    }

    /**
     * 启动定时清理任务
     *
     * 每60秒执行一次，清理所有过期的Token
     * 防止内存泄漏
     */
    private void startTokenCleaner() {
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            // 清理过期的Token
            tokens.entrySet().removeIf(entry -> entry.getValue().expireTime < now);
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 关闭服务
     */
    public void shutdown() {
        cleaner.shutdown();
    }

    /**
     * Token信息内部类
     */
    private static class TokenInfo {
        /** 玩家ID */
        final Long playerId;
        
        /** 过期时间（时间戳） */
        long expireTime;

        TokenInfo(Long playerId, long expireTime) {
            this.playerId = playerId;
            this.expireTime = expireTime;
        }
    }
}

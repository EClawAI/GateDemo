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

public class TokenService {

    private static final Logger logger = LoggerFactory.getLogger(TokenService.class);

    private final Map<String, TokenInfo> tokens = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final long tokenExpireSeconds;
    private final ScheduledExecutorService cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "token-cleaner");
        t.setDaemon(true);
        return t;
    });

    public TokenService(long tokenExpireSeconds) {
        this.tokenExpireSeconds = tokenExpireSeconds;
        startTokenCleaner();
    }

    public String generateToken(Long playerId) {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);

        TokenInfo info = new TokenInfo(playerId, System.currentTimeMillis() + tokenExpireSeconds * 1000);
        tokens.put(token, info);

        logger.info("Generated token for player: {}", playerId);
        return token;
    }

    public boolean validateToken(String token) {
        TokenInfo info = tokens.get(token);
        if (info == null) {
            return false;
        }

        if (System.currentTimeMillis() > info.expireTime) {
            tokens.remove(token);
            logger.info("Token expired for player: {}", info.playerId);
            return false;
        }

        return true;
    }

    public Long getPlayerId(String token) {
        TokenInfo info = tokens.get(token);
        return info != null ? info.playerId : null;
    }

    public void removeToken(String token) {
        tokens.remove(token);
    }

    public void refreshToken(String token) {
        TokenInfo info = tokens.get(token);
        if (info != null) {
            info.expireTime = System.currentTimeMillis() + tokenExpireSeconds * 1000;
        }
    }

    private void startTokenCleaner() {
        cleaner.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            tokens.entrySet().removeIf(entry -> entry.getValue().expireTime < now);
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {
        cleaner.shutdown();
    }

    private static class TokenInfo {
        final Long playerId;
        long expireTime;

        TokenInfo(Long playerId, long expireTime) {
            this.playerId = playerId;
            this.expireTime = expireTime;
        }
    }
}

package com.clawai.gatedemo.gate.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class TokenServiceTest {

    private static final String TEST_SECRET = "DefaultGateDemoSecretKeyForHMACSHA256Auth!";
    private TokenService tokenService;

    @BeforeEach
    void setUp() {
        tokenService = new TokenService(TEST_SECRET);
    }

    private String createToken(String subject, String jti, Date expiration) {
        SecretKey key = Keys.hmacShaKeyFor(TEST_SECRET.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(subject)
                .id(jti)
                .expiration(expiration)
                .signWith(key)
                .compact();
    }

    @Test
    void validateToken_withValidToken_returnsClaims() {
        String token = createToken("12345", "jti-001", new Date(System.currentTimeMillis() + 3600_000));
        Claims claims = tokenService.validateToken(token);
        assertNotNull(claims);
        assertEquals("12345", claims.getSubject());
        assertEquals("jti-001", claims.getId());
    }

    @Test
    void validateToken_withExpiredToken_returnsNull() {
        String token = createToken("12345", "jti-002", new Date(System.currentTimeMillis() - 1000));
        Claims claims = tokenService.validateToken(token);
        assertNull(claims);
    }

    @Test
    void validateToken_withInvalidToken_returnsNull() {
        String invalidToken = "invalid.jwt.token";
        Claims claims = tokenService.validateToken(invalidToken);
        assertNull(claims);
    }

    @Test
    void validateToken_withWrongSignature_returnsNull() {
        SecretKey wrongKey = Keys.hmacShaKeyFor("WrongSecretKeyForHMACSHA256Auth!!".getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("12345")
                .id("jti-003")
                .expiration(new Date(System.currentTimeMillis() + 3600_000))
                .signWith(wrongKey)
                .compact();
        Claims claims = tokenService.validateToken(token);
        assertNull(claims);
    }

    @Test
    void validateToken_subjectCanBeParsedAsPlayerId() {
        String token = createToken("99999", "jti-004", new Date(System.currentTimeMillis() + 3600_000));
        Claims claims = tokenService.validateToken(token);
        assertNotNull(claims);
        assertEquals(99999L, Long.parseLong(claims.getSubject()));
    }
}

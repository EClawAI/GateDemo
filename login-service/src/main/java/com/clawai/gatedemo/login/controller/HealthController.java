package com.clawai.gatedemo.login.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private static final Logger logger = LoggerFactory.getLogger(HealthController.class);

    private final RedisTemplate<String, Object> redisTemplate;

    public HealthController(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        String redisStatus = checkRedis();
        String overallStatus = "UP".equals(redisStatus) ? "UP" : "DOWN";
        
        result.put("status", overallStatus);
        Map<String, String> components = new LinkedHashMap<>();
        components.put("redis", redisStatus);
        result.put("components", components);
        
        int statusCode = "UP".equals(overallStatus) ? 200 : 503;
        return ResponseEntity.status(statusCode).body(result);
    }

    private String checkRedis() {
        try {
            redisTemplate.getConnectionFactory().getConnection().ping();
            return "UP";
        } catch (Exception e) {
            logger.warn("Redis health check failed: {}", e.getMessage());
            return "DOWN";
        }
    }
}

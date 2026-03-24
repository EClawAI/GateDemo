package com.clawai.gatedemo.gate.resilience;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 注册网关韧性相关 Bean：按玩家/全局限流器抑制滥用，gRPC 熔断器在下游 game-service 异常时快速失败、保护网关。
 */
@Configuration
public class ResilienceConfig {

    @Bean("perPlayerRateLimiter")
    public RateLimiter perPlayerRateLimiter(
            @Value("${gate.ratelimit.per-player.max-requests:60}") int maxRequests,
            @Value("${gate.ratelimit.per-player.window-ms:60000}") long windowMs) {
        return new RateLimiter(maxRequests, windowMs);
    }

    @Bean("globalRateLimiter")
    public RateLimiter globalRateLimiter(
            @Value("${gate.ratelimit.global.max-requests:10000}") int maxRequests,
            @Value("${gate.ratelimit.global.window-ms:1000}") long windowMs) {
        return new RateLimiter(maxRequests, windowMs);
    }

    @Bean
    public CircuitBreaker grpcCircuitBreaker(
            @Value("${gate.circuitbreaker.failure-threshold:5}") int failureThreshold,
            @Value("${gate.circuitbreaker.reset-timeout-ms:30000}") long resetTimeoutMs) {
        return new CircuitBreaker(failureThreshold, resetTimeoutMs);
    }
}

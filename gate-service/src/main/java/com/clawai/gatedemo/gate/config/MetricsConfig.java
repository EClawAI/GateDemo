package com.clawai.gatedemo.gate.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 网关指标配置：注册 Prometheus 注册表，供 {@code /metrics} 等端点拉取（不依赖 Spring Boot Actuator）。
 */
@Configuration
public class MetricsConfig {

    /**
     * @return 默认配置的 Prometheus 指标注册表，可 scrape 为 Prometheus 文本格式
     */
    @Bean
    public PrometheusMeterRegistry prometheusMeterRegistry() {
        return new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
    }

    /**
     * @param prometheusMeterRegistry 与上行 Bean 同一实例，供 Micrometer 通用 {@link MeterRegistry} 注入点使用
     * @return 作为全局 {@link MeterRegistry} 的 Prometheus 实现
     */
    @Bean
    public MeterRegistry meterRegistry(PrometheusMeterRegistry prometheusMeterRegistry) {
        return prometheusMeterRegistry;
    }
}

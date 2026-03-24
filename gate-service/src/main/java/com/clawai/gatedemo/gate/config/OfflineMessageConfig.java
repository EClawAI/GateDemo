package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定 {@code offline-message.*} 配置：离线消息堆积阈值、开关与最长保留天数，供网关侧离线策略使用。
 */
@Configuration
@ConfigurationProperties(prefix = "offline-message")
public class OfflineMessageConfig {

    private int threshold = 200;
    private boolean enabled = true;
    private long maxOfflineDays = 7;

    public int getThreshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaxOfflineDays() {
        return maxOfflineDays;
    }

    public void setMaxOfflineDays(long maxOfflineDays) {
        this.maxOfflineDays = maxOfflineDays;
    }
}

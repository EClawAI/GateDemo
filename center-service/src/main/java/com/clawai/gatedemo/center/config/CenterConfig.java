package com.clawai.gatedemo.center.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定 {@code center.*}，集中管理需下发给客户端的版本策略、SDK/登录端点与公告等内容。
 */
@Data
@Component
@ConfigurationProperties(prefix = "center")
public class CenterConfig {

    private ServerConfig server = new ServerConfig();
    private VersionConfig version = new VersionConfig();
    private SdkConfig sdk = new SdkConfig();
    private LoginConfig login = new LoginConfig();
    /** 公告列表；对外接口通常只取首条映射到响应 */
    private List<AnnouncementConfig> announcements = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private int port = 8080;
    }

    @Data
    public static class VersionConfig {
        private String version = "1.0.0";
        private String minVersion = "1.0.0";
        private boolean forceUpdate = false;
        private String updateUrl = "https://example.com/update";
    }

    @Data
    public static class SdkConfig {
        private String host = "sdk.example.com";
        private int port = 8443;
    }

    @Data
    public static class LoginConfig {
        private String host = "login.example.com";
        private int port = 8081;
    }

    @Data
    public static class AnnouncementConfig {
        private String title;
        private String content;
        private String type = "normal";
    }
}

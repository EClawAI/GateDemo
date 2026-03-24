package com.clawai.gatedemo.login.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 绑定 {@code login.*} 配置，为登录服务提供监听端口、Redis 连接及推荐游戏列表等运行参数。
 */
@Data
@Component
@ConfigurationProperties(prefix = "login")
public class LoginConfig {

    private ServerConfig server = new ServerConfig();
    private RedisConfig redis = new RedisConfig();
    private List<RecommendGameConfig> recommendGames = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private int port = 8081;
    }

    @Data
    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        private String password = "";
        private int database = 0;
    }

    @Data
    public static class RecommendGameConfig {
        private int gameId;
        private String name;
        private int priority = 1;
    }
}

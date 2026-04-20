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
    /** 推荐服列表，供无上次可用服时的路由兜底；priority 越小越优先 */
    private List<RecommendGameConfig> recommendGames = new ArrayList<>();

    @Data
    public static class ServerConfig {
        private int port = 8081;
    }

    @Data
    public static class RedisConfig {
        private String host = "localhost";
        private int port = 6379;
        /** Redis 6+ ACL 用户名；空表示仅密码认证 */
        private String username = "";
        private String password = "";
        private int database = 0;
    }

    @Data
    public static class RecommendGameConfig {
        private int gameId;
        private String name;
        /** 路由时的排序权重，数值越小优先级越高 */
        private int priority = 1;
    }
}

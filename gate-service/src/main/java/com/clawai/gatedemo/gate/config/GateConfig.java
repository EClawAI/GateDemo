package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Gate 服务配置类
 * 
 * 功能说明：
 * 从 application.yml 读取配置
 * 
 * 配置项：
 * - gate.id: 网关 ID（如：gate-01）
 * - gate.host: 监听地址（默认：0.0.0.0）
 * - gate.port: WebSocket 端口（默认：8888）
 * - gate.game.host: Game 服务地址（默认：localhost）
 * - gate.game.port: Game 服务端口（默认：8081）
 * - gate.player.heartbeat-interval: 心跳间隔（秒）
 * 
 * @author clawAI
 * @since 2026-03-05
 */
@Configuration
@ConfigurationProperties(prefix = "gate")
public class GateConfig {

    /**
     * 网关 ID
     * 用于标识不同的 Gate 实例
     */
    private String id = "gate-01";

    /**
     * 监听地址
     * 0.0.0.0 表示监听所有网卡
     */
    private String host = "0.0.0.0";

    /**
     * WebSocket 端口
     * 玩家通过此端口连接
     */
    private int port = 8888;

    /**
     * Game 服务配置
     */
    private Game game = new Game();

    /**
     * 玩家配置
     */
    private Player player = new Player();

    /**
     * Game 服务配置类
     */
    public static class Game {
        private String host = "localhost";
        private int port = 8081;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    /**
     * 玩家配置类
     */
    public static class Player {
        /**
         * 心跳间隔（秒）
         * 玩家每隔多久发送一次心跳
         */
        private int heartbeatInterval = 60;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

    // ==================== Getters and Setters ====================

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Game getGame() { return game; }
    public void setGame(Game game) { this.game = game; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
}
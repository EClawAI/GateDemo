package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
 * - gate.games: Game 服务列表（支持多实例）
 *   - id: Game ID
 *   - host: Game 服务地址
 *   - port: gRPC 端口（默认 9090）
 * - gate.player.heartbeat-interval: 心跳间隔（秒）
 * 
 * @author clawAI
 * @since 2026-03-06
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
     * Game 服务列表（支持多实例）
     * 配置示例：
     * gate:
     *   games:
     *     - id: 1001
     *       host: localhost
     *       port: 9091
     *     - id: 1002
     *       host: localhost
     *       port: 9092
     */
    private List<GameInstance> games = new ArrayList<>();

    /**
     * 玩家配置
     */
    private Player player = new Player();

    /**
     * Game 服务实例配置
     */
    public static class GameInstance {
        private int id;
        private String host = "localhost";
        private int port = 9090;

        public int getId() { return id; }
        public void setId(int id) { this.id = id; }
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
    public List<GameInstance> getGames() { return games; }
    public void setGames(List<GameInstance> games) { this.games = games; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
}
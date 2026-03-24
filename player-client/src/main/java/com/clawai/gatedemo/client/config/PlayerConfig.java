package com.clawai.gatedemo.client.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 绑定 {@code player.*} 配置（主机、端口、玩家 ID、心跳间隔等），供 WebFlux 演示客户端与网关注册、保活参数一致。
 */
@Configuration
@ConfigurationProperties(prefix = "player")
public class PlayerConfig {

    /** 演示玩家标识，与网关鉴权、报文中的 player_id 一致 */
    private Long playerId = 100001L;
    /** Gate WebSocket 主机名 */
    private String host = "localhost";
    /** Gate WebSocket 端口 */
    private int port = 8888;
    /** 文本协议心跳发送间隔（秒），需与网关侧空闲检测策略协调 */
    private int heartbeatInterval = 30;

    public Long getPlayerId() { return playerId; }
    public void setPlayerId(Long playerId) { this.playerId = playerId; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public int getHeartbeatInterval() { return heartbeatInterval; }
    public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
}
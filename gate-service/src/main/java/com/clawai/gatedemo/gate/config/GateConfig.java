package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gate")
public class GateConfig {

    private String id = "gate-01";
    private String host = "0.0.0.0";
    private int port = 8888;
    private Game game = new Game();
    private Player player = new Player();

    public static class Game {
        private String host = "localhost";
        private int port = 8081;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public static class Player {
        private int heartbeatInterval = 60;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
    }

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
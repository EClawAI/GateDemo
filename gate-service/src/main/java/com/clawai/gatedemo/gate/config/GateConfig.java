package com.clawai.gatedemo.gate.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "gate")
public class GateConfig {

    private String id = "gate-01";
    private String host = "0.0.0.0";
    private int port = 8888;
    private Redis redis = new Redis();
    private Player player = new Player();

    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private Stream stream = new Stream();

        public static class Stream {
            private String consumerGroup;
            private int blockMs = 5000;
            private int count = 100;

            public String getConsumerGroup() { return consumerGroup; }
            public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
            public int getBlockMs() { return blockMs; }
            public void setBlockMs(int blockMs) { this.blockMs = blockMs; }
            public int getCount() { return count; }
            public void setCount(int count) { this.count = count; }
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public Stream getStream() { return stream; }
        public void setStream(Stream stream) { this.stream = stream; }
    }

    public static class Player {
        private int heartbeatInterval = 60;
        private int mapTtl = 300;

        public int getHeartbeatInterval() { return heartbeatInterval; }
        public void setHeartbeatInterval(int heartbeatInterval) { this.heartbeatInterval = heartbeatInterval; }
        public int getMapTtl() { return mapTtl; }
        public void setMapTtl(int mapTtl) { this.mapTtl = mapTtl; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }
    public Player getPlayer() { return player; }
    public void setPlayer(Player player) { this.player = player; }
}
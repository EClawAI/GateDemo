package com.clawai.gatedemo.game.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "game")
public class GameConfig {

    private String id = "game-1001";
    private Redis redis = new Redis();

    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private Stream stream = new Stream();

        public static class Stream {
            private String consumerGroup;

            public String getConsumerGroup() { return consumerGroup; }
            public void setConsumerGroup(String consumerGroup) { this.consumerGroup = consumerGroup; }
        }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
        public Stream getStream() { return stream; }
        public void setStream(Stream stream) { this.stream = stream; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }
}
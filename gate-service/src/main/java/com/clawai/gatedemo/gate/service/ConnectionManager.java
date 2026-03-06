package com.clawai.gatedemo.gate.service;

import com.clawai.gatedemo.gate.model.PlayerConnection;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Service
public class ConnectionManager {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionManager.class);

    private final Map<Long, PlayerConnection> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService heartbeatChecker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat-checker");
        t.setDaemon(true);
        return t;
    });

    private long heartbeatTimeoutSeconds = 300;
    private int maxConnections = 10000;

    public ConnectionManager() {
        startHeartbeatChecker();
    }

    public void setHeartbeatTimeoutSeconds(long seconds) {
        this.heartbeatTimeoutSeconds = seconds;
    }

    public void setMaxConnections(int max) {
        this.maxConnections = max;
    }

    public void registerConnection(Long playerId, Channel channel) {
        if (connections.size() >= maxConnections) {
            logger.warn("Max connections reached: {}", maxConnections);
            channel.close();
            return;
        }

        PlayerConnection conn = new PlayerConnection(playerId, channel);
        connections.put(playerId, conn);
        logger.info("Player {} connected, total: {}", playerId, connections.size());
    }

    public void authenticate(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        if (conn != null) {
            conn.setState(PlayerConnection.State.AUTHENTICATED);
            logger.info("Player {} authenticated", playerId);
        }
    }

    public void unregisterConnection(Long playerId) {
        PlayerConnection removed = connections.remove(playerId);
        if (removed != null) {
            logger.info("Player {} disconnected, total: {}", playerId, connections.size());
        }
    }

    public void renewHeartbeat(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        if (conn != null) {
            conn.updateHeartbeat();
        }
    }

    public boolean hasPlayer(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        return conn != null && conn.getState() == PlayerConnection.State.AUTHENTICATED;
    }

    public Channel getPlayerChannel(Long playerId) {
        PlayerConnection conn = connections.get(playerId);
        return conn != null ? conn.getChannel() : null;
    }

    public int getOnlineCount() {
        return connections.size();
    }

    private void startHeartbeatChecker() {
        heartbeatChecker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            long timeout = heartbeatTimeoutSeconds * 1000;

            connections.entrySet().removeIf(entry -> {
                PlayerConnection conn = entry.getValue();
                if (conn.getState() == PlayerConnection.State.AUTHENTICATED) {
                    if (now - conn.getLastHeartbeatTime() > timeout) {
                        logger.warn("Player {} heartbeat timeout", conn.getPlayerId());
                        if (conn.getChannel() != null) {
                            conn.getChannel().close();
                        }
                        return true;
                    }
                }
                return false;
            });
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        heartbeatChecker.shutdown();
    }
}

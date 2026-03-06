package com.clawai.gatedemo.gate.cluster;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class GateClusterManager {

    private static final Logger logger = LoggerFactory.getLogger(GateClusterManager.class);

    private final String instanceId;
    private final Map<String, GateInstance> instances = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "cluster-manager");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean registered = false;

    public GateClusterManager() {
        String id;
        try {
            id = InetAddress.getLocalHost().getHostAddress() + ":" + System.currentTimeMillis();
        } catch (Exception e) {
            id = "unknown:" + System.currentTimeMillis();
        }
        this.instanceId = id;
        startHeartbeat();
    }

    public void registerInstance(int port, int onlinePlayers) {
        GateInstance instance = new GateInstance(instanceId, getLocalIp(), port, onlinePlayers, System.currentTimeMillis());
        instances.put(instanceId, instance);
        registered = true;
        logger.info("Registered gate instance: {}", instanceId);
    }

    public void updateOnlineCount(int count) {
        GateInstance instance = instances.get(instanceId);
        if (instance != null) {
            instance.setOnlinePlayers(count);
            instance.setLastHeartbeat(System.currentTimeMillis());
        }
    }

    public void unregisterInstance() {
        instances.remove(instanceId);
        registered = false;
        logger.info("Unregistered gate instance: {}", instanceId);
    }

    public Map<String, GateInstance> getAllInstances() {
        return new ConcurrentHashMap<>(instances);
    }

    public GateInstance getInstance(String instanceId) {
        return instances.get(instanceId);
    }

    public String getInstanceId() {
        return instanceId;
    }

    public boolean isRegistered() {
        return registered;
    }

    private void startHeartbeat() {
        scheduler.scheduleAtFixedRate(() -> {
            GateInstance instance = instances.get(instanceId);
            if (instance != null) {
                instance.setLastHeartbeat(System.currentTimeMillis());
            }

            instances.entrySet().removeIf(entry -> {
                long inactive = System.currentTimeMillis() - entry.getValue().getLastHeartbeat();
                if (inactive > 120000) {
                    logger.info("Removed inactive gate instance: {}", entry.getKey());
                    return true;
                }
                return false;
            });
        }, 30, 30, TimeUnit.SECONDS);
    }

    public void shutdown() {
        unregisterInstance();
        scheduler.shutdown();
    }

    private String getLocalIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    public static class GateInstance {
        private final String instanceId;
        private final String host;
        private final int port;
        private volatile int onlinePlayers;
        private volatile long lastHeartbeat;

        public GateInstance(String instanceId, String host, int port, int onlinePlayers, long lastHeartbeat) {
            this.instanceId = instanceId;
            this.host = host;
            this.port = port;
            this.onlinePlayers = onlinePlayers;
            this.lastHeartbeat = lastHeartbeat;
        }

        public String getInstanceId() { return instanceId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getOnlinePlayers() { return onlinePlayers; }
        public void setOnlinePlayers(int onlinePlayers) { this.onlinePlayers = onlinePlayers; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
    }
}

package com.clawai.gatedemo.gate.monitor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class MetricsCollector {

    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    private final Map<Short, MessageMetrics> messageMetrics = new ConcurrentHashMap<>();
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger onlinePlayers = new AtomicInteger(0);
    private final AtomicLong totalMessages = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-collector");
        t.setDaemon(true);
        return t;
    });

    public MetricsCollector() {
        startMetricsReporter();
    }

    public void recordMessage(short messageId, long processTimeMs) {
        totalMessages.incrementAndGet();
        MessageMetrics metrics = messageMetrics.computeIfAbsent(messageId, k -> new MessageMetrics());
        metrics.record(processTimeMs);
    }

    public void recordConnection() {
        totalConnections.incrementAndGet();
    }

    public void recordDisconnection() {
    }

    public void updateOnlinePlayers(int count) {
        onlinePlayers.set(count);
    }

    public void recordError() {
        totalErrors.incrementAndGet();
    }

    public Map<Short, MessageMetrics> getMessageMetrics() {
        return new ConcurrentHashMap<>(messageMetrics);
    }

    public int getTotalConnections() {
        return totalConnections.get();
    }

    public int getOnlinePlayers() {
        return onlinePlayers.get();
    }

    public long getTotalMessages() {
        return totalMessages.get();
    }

    public long getTotalErrors() {
        return totalErrors.get();
    }

    public double getQps() {
        return totalMessages.get() / 60.0;
    }

    private void startMetricsReporter() {
        scheduler.scheduleAtFixedRate(() -> {
            logger.info("=== Metrics Report ===");
            logger.info("Online Players: {}", onlinePlayers.get());
            logger.info("Total Messages: {}", totalMessages.get());
            logger.info("Total Errors: {}", totalErrors.get());
            logger.info("Total Connections: {}", totalConnections.get());

            messageMetrics.forEach((msgId, metrics) -> {
                logger.info("  Message {}: count={}, avgTime={}ms",
                        msgId, metrics.getCount(), metrics.getAvgTime());
            });
        }, 60, 60, TimeUnit.SECONDS);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public static class MessageMetrics {
        private final AtomicInteger count = new AtomicInteger(0);
        private final AtomicLong totalTime = new AtomicLong(0);

        public void record(long processTimeMs) {
            count.incrementAndGet();
            totalTime.addAndGet(processTimeMs);
        }

        public int getCount() {
            return count.get();
        }

        public long getTotalTime() {
            return totalTime.get();
        }

        public double getAvgTime() {
            int c = count.get();
            return c > 0 ? (double) totalTime.get() / c : 0;
        }
    }
}

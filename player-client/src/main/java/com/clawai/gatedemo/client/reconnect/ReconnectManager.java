package com.clawai.gatedemo.client.reconnect;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 在断线或失败路径上按 {@link ReconnectPolicy} 调度延迟重连，并通过回调暴露尝试/成功/失败/用尽次数等生命周期。
 */
public class ReconnectManager {

    private static final Logger logger = LoggerFactory.getLogger(ReconnectManager.class);

    private final ReconnectPolicy policy;
    private final ReconnectCallback callback;
    private final ScheduledExecutorService scheduler;

    private ScheduledFuture<?> reconnectTask;
    private volatile State state = State.IDLE;

    public enum State {
        IDLE, CONNECTING, RECONNECTING, CONNECTED, DISCONNECTED
    }

    public interface ReconnectCallback {
        void onReconnectAttempt(int attempt);
        void onReconnectSuccess();
        void onReconnectFailed(int attempt, Throwable cause);
        void onReconnectMaxAttemptsReached();
    }

    public ReconnectManager(ReconnectPolicy policy, ReconnectCallback callback) {
        this.policy = policy;
        this.callback = callback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reconnect-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    public void startReconnect() {
        if (state == State.RECONNECTING || state == State.CONNECTING) {
            logger.warn("Already reconnecting or connecting");
            return;
        }

        state = State.RECONNECTING;
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!policy.shouldRetry()) {
            logger.warn("Max reconnect attempts reached");
            state = State.DISCONNECTED;
            callback.onReconnectMaxAttemptsReached();
            return;
        }

        long delay = policy.getNextDelay();
        int attempt = policy.getRetryCount() + 1;

        logger.info("Scheduling reconnect attempt {} in {}ms", attempt, delay);
        callback.onReconnectAttempt(attempt);

        reconnectTask = scheduler.schedule(() -> {
            policy.onRetry();
        }, delay, TimeUnit.MILLISECONDS);
    }

    public void onConnectSuccess() {
        cancelReconnect();
        state = State.CONNECTED;
        policy.reset();
        callback.onReconnectSuccess();
        logger.info("Reconnect successful");
    }

    public void onConnectFailed(Throwable cause) {
        if (state == State.RECONNECTING) {
            int attempt = policy.getRetryCount();
            callback.onReconnectFailed(attempt, cause);
            scheduleReconnect();
        }
    }

    public void onDisconnect() {
        if (state == State.CONNECTED) {
            state = State.DISCONNECTED;
        }
    }

    public void cancelReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }
    }

    public void shutdown() {
        cancelReconnect();
        scheduler.shutdown();
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }
}

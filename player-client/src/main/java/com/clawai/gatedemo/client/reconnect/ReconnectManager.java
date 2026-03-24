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

    /**
     * 按策略延迟后触发下一次 {@link ReconnectPolicy#onRetry()}；用尽次数时通知 {@link ReconnectCallback#onReconnectMaxAttemptsReached()}。
     */
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

    /**
     * 连接建立成功：取消挂起重连任务、重置策略计数并回调成功。
     */
    public void onConnectSuccess() {
        cancelReconnect();
        state = State.CONNECTED;
        policy.reset();
        callback.onReconnectSuccess();
        logger.info("Reconnect successful");
    }

    /**
     * 在仍处于 {@link State#RECONNECTING} 时记录失败并继续排期下一次尝试。
     *
     * @param cause 底层连接失败原因
     */
    public void onConnectFailed(Throwable cause) {
        if (state == State.RECONNECTING) {
            int attempt = policy.getRetryCount();
            callback.onReconnectFailed(attempt, cause);
            scheduleReconnect();
        }
    }

    /**
     * 在曾处于已连接态时标记为断开，供上层决定是否调用 {@link #startReconnect()}。
     */
    public void onDisconnect() {
        if (state == State.CONNECTED) {
            state = State.DISCONNECTED;
        }
    }

    /** 取消已调度但未执行的重连任务，不关闭调度器 */
    public void cancelReconnect() {
        if (reconnectTask != null && !reconnectTask.isDone()) {
            reconnectTask.cancel(false);
        }
    }

    /** 取消挂起任务并关闭调度线程池 */
    public void shutdown() {
        cancelReconnect();
        scheduler.shutdown();
    }

    /** @return 当前连接/重连状态 */
    public State getState() {
        return state;
    }

    /**
     * 供外部同步状态机（例如开始主动连接时置为 {@link State#CONNECTING}）。
     *
     * @param state 新状态
     */
    public void setState(State state) {
        this.state = state;
    }
}

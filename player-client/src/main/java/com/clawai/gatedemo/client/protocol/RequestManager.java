package com.clawai.gatedemo.client.protocol;

import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 管理二进制请求-响应关联：为出站报文分配 {@code requestId}、挂起回调并在超时或回包时完成或清理，
 * 支撑客户端侧的半双工/异步 RPC 语义。
 */
public class RequestManager {

    private static final Logger logger = LoggerFactory.getLogger(RequestManager.class);

    /** requestId 到挂起请求的映射，供回包匹配与超时清理 */
    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    /** 单线程调度超时任务；守护线程不阻止 JVM 退出 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "request-manager");
        t.setDaemon(true);
        return t;
    });

    /** 下一个待分配的 requestId；演示场景下多为单线程发送，未使用原子递增 */
    private int nextRequestId = 1;

    /**
     * 为出站报文写入单调 {@code requestId} 并登记回调，超时后从挂起表移除并记录告警。
     *
     * @param messageId 业务消息类型 ID（用于日志）
     * @param message     待发报文，其头中的 requestId 会被覆盖
     * @param callback    收到匹配回包时调用；超时路径当前仅打日志
     * @param timeoutMs   超时毫秒数
     * @return 本次请求分配的 requestId
     */
    public int sendRequest(short messageId, WrappedMessage message, Consumer<WrappedMessage> callback, long timeoutMs) {
        int requestId = nextRequestId++;
        message.getHeader().setRequestId(requestId);

        PendingRequest request = new PendingRequest(callback, timeoutMs);
        pendingRequests.put(requestId, request);

        scheduler.schedule(() -> {
            PendingRequest removed = pendingRequests.remove(requestId);
            if (removed != null && !removed.completed) {
                logger.warn("Request {} timeout", requestId);
                removed.onTimeout();
            }
        }, timeoutMs, TimeUnit.MILLISECONDS);

        logger.debug("Sent request: id={}, msgId={}", requestId, messageId);
        return requestId;
    }

    /**
     * 根据回包中的 requestId 完成挂起请求并执行回调；若无挂起项则返回 false。
     *
     * @param requestId 与出站时一致的请求号
     * @param message     完整回包
     * @return 是否找到并处理了挂起请求
     */
    public boolean onResponse(int requestId, WrappedMessage message) {
        PendingRequest request = pendingRequests.remove(requestId);
        if (request != null) {
            request.completed = true;
            if (request.callback != null) {
                request.callback.accept(message);
            }
            logger.debug("Received response for request: {}", requestId);
            return true;
        }
        logger.warn("No pending request for response: {}", requestId);
        return false;
    }

    /**
     * 主动移除挂起项（例如连接断开），不触发回调。
     *
     * @param requestId 待取消的请求号
     */
    public void cancelRequest(int requestId) {
        pendingRequests.remove(requestId);
    }

    /** 停止超时调度线程池；客户端关闭时调用 */
    public void shutdown() {
        scheduler.shutdown();
    }

    private static class PendingRequest {
        final Consumer<WrappedMessage> callback;
        final long timeoutMs;
        /** 与超时任务协同，避免超时与回包双路径重复处理 */
        volatile boolean completed = false;

        PendingRequest(Consumer<WrappedMessage> callback, long timeoutMs) {
            this.callback = callback;
            this.timeoutMs = timeoutMs;
        }

        void onTimeout() {
            logger.warn("Request timeout");
        }
    }
}

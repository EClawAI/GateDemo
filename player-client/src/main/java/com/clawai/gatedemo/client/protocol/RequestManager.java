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

public class RequestManager {

    private static final Logger logger = LoggerFactory.getLogger(RequestManager.class);

    private final Map<Integer, PendingRequest> pendingRequests = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "request-manager");
        t.setDaemon(true);
        return t;
    });

    private int nextRequestId = 1;

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

    public void cancelRequest(int requestId) {
        pendingRequests.remove(requestId);
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    private static class PendingRequest {
        final Consumer<WrappedMessage> callback;
        final long timeoutMs;
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

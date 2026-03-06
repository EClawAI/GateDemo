package com.clawai.gatedemo.gate.router;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HandlerRegistry {

    private final Map<Short, MessageHandler> handlers = new ConcurrentHashMap<>();

    public void register(short messageId, MessageHandler handler) {
        handlers.put(messageId, handler);
    }

    public void unregister(short messageId) {
        handlers.remove(messageId);
    }

    public MessageHandler getHandler(short messageId) {
        return handlers.get(messageId);
    }

    public boolean hasHandler(short messageId) {
        return handlers.containsKey(messageId);
    }

    public Map<Short, MessageHandler> getAllHandlers() {
        return new ConcurrentHashMap<>(handlers);
    }

    public void clear() {
        handlers.clear();
    }
}

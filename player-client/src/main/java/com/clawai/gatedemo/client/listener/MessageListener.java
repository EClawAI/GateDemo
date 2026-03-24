package com.clawai.gatedemo.client.listener;

import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 按消息 ID 分发下行业务：注册回调与默认日志兜底，将协议层解析结果交给业务或测试逻辑。
 */
public class MessageListener {

    private static final Logger logger = LoggerFactory.getLogger(MessageListener.class);

    private final Map<Short, Consumer<WrappedMessage>> listeners = new ConcurrentHashMap<>();
    private final Consumer<WrappedMessage> defaultListener;

    public MessageListener() {
        this.defaultListener = msg -> logger.debug("Received message: {}", msg);
    }

    public void register(short messageId, Consumer<WrappedMessage> listener) {
        listeners.put(messageId, listener);
        logger.info("Registered listener for messageId: {}", messageId);
    }

    public void unregister(short messageId) {
        listeners.remove(messageId);
    }

    public void onMessage(WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return;
        }

        short messageId = message.getHeader().getMessageId();
        Consumer<WrappedMessage> listener = listeners.get(messageId);

        if (listener != null) {
            try {
                listener.accept(message);
            } catch (Exception e) {
                logger.error("Error in message listener for {}: {}", messageId, e.getMessage(), e);
            }
        } else {
            defaultListener.accept(message);
        }
    }

    public void clear() {
        listeners.clear();
    }

    public Map<Short, Consumer<WrappedMessage>> getListeners() {
        return new ConcurrentHashMap<>(listeners);
    }
}

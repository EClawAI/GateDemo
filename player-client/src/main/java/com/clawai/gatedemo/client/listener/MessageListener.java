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

    /** 按消息 ID 注册的下行回调；并发注册与 {@link #onMessage(WrappedMessage)} 分发场景下使用线程安全 Map */
    private final Map<Integer, Consumer<WrappedMessage>> listeners = new ConcurrentHashMap<>();
    /** 未注册消息 ID 时的兜底处理，默认仅打调试日志 */
    private final Consumer<WrappedMessage> defaultListener;

    public MessageListener() {
        this.defaultListener = msg -> logger.debug("Received message: {}", msg);
    }

    /**
     * 为指定消息 ID 注册消费者；后注册覆盖先注册。
     *
     * @param messageId 协议短消息 ID
     * @param listener  收到对应下行报文时在调用线程执行
     */
    public void register(int messageId, Consumer<WrappedMessage> listener) {
        listeners.put(messageId, listener);
        logger.info("Registered listener for messageId: {}", messageId);
    }

    /**
     * 移除指定消息 ID 的监听；若无注册则为空操作。
     *
     * @param messageId 协议短消息 ID
     */
    public void unregister(int messageId) {
        listeners.remove(messageId);
    }

    /**
     * 根据报文头中的消息 ID 分发到已注册回调；无匹配时使用默认监听器。
     * 监听器异常会被捕获并记录，不影响其他消息。
     *
     * @param message 解码后的完整报文，含头与体
     */
    public void onMessage(WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return;
        }

        int messageId = message.getHeader().getMessageId();
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

    /** 清空所有已注册监听，用于断线重连或测试隔离 */
    public void clear() {
        listeners.clear();
    }

    /**
     * 返回当前注册表的快照副本，避免外部直接修改内部 Map。
     *
     * @return 消息 ID 到监听器的拷贝
     */
    public Map<Integer, Consumer<WrappedMessage>> getListeners() {
        return new ConcurrentHashMap<>(listeners);
    }
}

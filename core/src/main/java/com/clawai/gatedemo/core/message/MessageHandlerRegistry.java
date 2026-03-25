package com.clawai.gatedemo.core.message;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 消息处理器注册表：messageId → { Parser, IMessageHandler } 映射。
 * 由 {@link MessageHandlerScanner} 自动填充，也支持手动 {@link #register}。
 */
public class MessageHandlerRegistry {

    private static final Logger logger = LoggerFactory.getLogger(MessageHandlerRegistry.class);

    /** Handler 注册条目：持有 parser 和 handler 引用。 */
    public record HandlerEntry(Parser<?> parser, IMessageHandler<?> handler, String messageName) {}

    private final Map<Integer, HandlerEntry> handlers = new ConcurrentHashMap<>();

    /**
     * 注册一个 handler。从 proto class 的 simpleName 查 {@link MessageRouteRegistry} 获取 id，
     * 从 class 反射获取 parser。
     */
    @SuppressWarnings("unchecked")
    public <T extends MessageLite> void register(Class<T> messageClass, IMessageHandler<T> handler) {
        String name = messageClass.getSimpleName();
        int id = MessageRouteRegistry.getIdByName(name);
        if (id == 0) {
            logger.warn("消息 {} 在 MessageRouteRegistry 中未注册，跳过 handler 注册", name);
            return;
        }

        Parser<T> parser = getParser(messageClass);
        if (parser == null) {
            logger.warn("无法获取消息 {} 的 Parser，跳过 handler 注册", name);
            return;
        }

        handlers.put(id, new HandlerEntry(parser, handler, name));
        logger.debug("注册 handler: {} -> id={}", name, id);
    }

    /** 按 messageId 查找 handler 条目。 */
    public HandlerEntry getHandler(int messageId) {
        return handlers.get(messageId);
    }

    public int size() {
        return handlers.size();
    }

    @SuppressWarnings("unchecked")
    private <T extends MessageLite> Parser<T> getParser(Class<T> messageClass) {
        try {
            Method method = messageClass.getMethod("parser");
            return (Parser<T>) method.invoke(null);
        } catch (Exception e) {
            logger.error("反射获取 parser 失败: {}", messageClass.getSimpleName(), e);
            return null;
        }
    }
}

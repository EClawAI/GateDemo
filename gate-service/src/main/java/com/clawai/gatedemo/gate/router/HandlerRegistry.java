package com.clawai.gatedemo.gate.router;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内消息号到 {@link MessageHandler} 的注册表，是 TCP 协议分发扩展点：新业务通过注册 messageId 接入而无需改核心管线。
 */
public class HandlerRegistry {

    private final Map<Short, MessageHandler> handlers = new ConcurrentHashMap<>();

    /**
     * @param messageId TCP 二进制协议中的消息号
     * @param handler   处理该消息号的处理器（覆盖同 ID 已有注册）
     */
    public void register(short messageId, MessageHandler handler) {
        handlers.put(messageId, handler);
    }

    /** @param messageId 要移除的消息号 */
    public void unregister(short messageId) {
        handlers.remove(messageId);
    }

    /** @return 已注册的处理器，未注册时返回 null */
    public MessageHandler getHandler(short messageId) {
        return handlers.get(messageId);
    }

    /** @return 是否已注册该消息号 */
    public boolean hasHandler(short messageId) {
        return handlers.containsKey(messageId);
    }

    /** @return 当前注册表的浅拷贝快照，避免外部直接修改内部 Map */
    public Map<Short, MessageHandler> getAllHandlers() {
        return new ConcurrentHashMap<>(handlers);
    }

    /** 清空全部注册，多用于测试或热重置场景。 */
    public void clear() {
        handlers.clear();
    }
}

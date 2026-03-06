package com.clawai.gatedemo.gate.router;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;

/**
 * 消息处理器接口 - 定义消息处理的规范
 *
 * 设计原理：
 * 使用策略模式，每个消息类型对应一个Handler。
 * Handler只关注自己的业务逻辑，职责单一，易于测试和扩展。
 *
 * 接口设计：
 * - handle(): 处理消息的核心方法
 * - shouldHandle(): 预处理判断，是否需要处理该消息
 *
 * 使用示例：
 * <pre>
 * // 注册处理器
 * dispatcher.register(0x1001, new AuthHandler());
 *
 * // 处理消息
 * MessageHandler handler = registry.getHandler(messageId);
 * if (handler != null) {
 *     handler.handle(ctx, message);
 * }
 * </pre>
 *
 * @see MessageDispatcher 默认分发器实现
 */
public interface MessageHandler {

    /**
     * 处理消息的核心方法
     *
     * @param ctx Netty通道上下文，用于发送响应
     * @param message 要处理的消息
     * @throws Exception 处理过程中的异常
     */
    void handle(ChannelHandlerContext ctx, WrappedMessage message) throws Exception;

    /**
     * 预处理判断
     *
     * 默认实现总是返回true，允许处理。
     * 子类可以重写此方法实现自定义的过滤逻辑。
     *
     * 使用场景：
     * - 根据玩家状态判断是否处理
     * - 根据消息内容判断是否处理
     *
     * @param message 要处理的消息
     * @return true表示需要处理，false表示跳过
     */
    default boolean shouldHandle(WrappedMessage message) {
        return true;
    }
}

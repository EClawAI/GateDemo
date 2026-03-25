package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.core.message.IMessageHandler;
import com.clawai.gatedemo.core.message.MessageContext;
import com.google.protobuf.MessageLite;

/**
 * Game 服务 handler 接口，在 core {@link IMessageHandler} 基础上将上下文收窄为
 * {@link GameMessageContext}（预加载 PlayerData、dirty 标记等）。
 * <p>
 * 开发者实现此接口时 handle 方法直接接收 {@code GameMessageContext}，
 * bridge default 方法完成从基类到子类的转型。
 *
 * @param <T> proto 消息类型
 */
public interface IGameHandler<T extends MessageLite> extends IMessageHandler<T> {

    /**
     * 处理 Game 消息（强类型上下文）。
     */
    void handle(GameMessageContext ctx, T message) throws Exception;

    /** 桥接 core 接口，向下转型。 */
    @Override
    default void handle(MessageContext ctx, T message) throws Exception {
        handle((GameMessageContext) ctx, message);
    }
}

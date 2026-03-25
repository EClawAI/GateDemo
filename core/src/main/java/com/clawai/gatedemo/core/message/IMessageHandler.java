package com.clawai.gatedemo.core.message;

import com.google.protobuf.MessageLite;

/**
 * 泛型消息处理器接口。T 为 protobuf 生成的消息类型，
 * 框架自动完成 parseFrom(body) 后将强类型对象传入 handle。
 *
 * @param <T> proto 消息类型
 */
@FunctionalInterface
public interface IMessageHandler<T extends MessageLite> {

    /**
     * 处理一条消息。
     *
     * @param ctx     消息上下文（基类或服务层子类）
     * @param message 已反序列化的 proto 消息
     */
    void handle(MessageContext ctx, T message) throws Exception;
}

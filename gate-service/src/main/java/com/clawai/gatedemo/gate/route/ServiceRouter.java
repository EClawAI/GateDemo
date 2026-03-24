package com.clawai.gatedemo.gate.route;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;

/**
 * 服务路由器抽象：Gate 根据 messageId 查到目标服务后，委托对应实现完成转发。
 * 每种后端服务（game、chat …）提供一个实现。
 */
public interface ServiceRouter {

    /** 此路由器负责的服务类型标识，如 "game"、"chat"。 */
    String serviceType();

    /**
     * 将消息转发到后端服务。
     *
     * @param ctx      当前连接上下文（可读取 channel attribute）
     * @param message  完整的二进制协议消息（header + raw body）
     * @param playerId 当前连接绑定的玩家 ID
     */
    void forward(ChannelHandlerContext ctx, WrappedMessage message, Long playerId);
}

package com.clawai.gatedemo.gate.router;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;

public interface MessageHandler {

    void handle(ChannelHandlerContext ctx, WrappedMessage message) throws Exception;

    default boolean shouldHandle(WrappedMessage message) {
        return true;
    }
}

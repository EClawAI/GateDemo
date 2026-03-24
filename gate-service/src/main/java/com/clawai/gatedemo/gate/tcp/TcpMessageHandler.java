package com.clawai.gatedemo.gate.tcp;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.router.MessageDispatcher;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Netty 入站处理器：记录连接生命周期，将协议层 {@link WrappedMessage} 交给 {@link MessageDispatcher}，完成 TCP 到业务路由的衔接。
 */
@Component
public class TcpMessageHandler extends SimpleChannelInboundHandler<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(TcpMessageHandler.class);

    private final MessageDispatcher dispatcher;

    public TcpMessageHandler(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("TCP client connected: {}", ctx.channel().remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
        if (message == null || message.getHeader() == null) {
            return;
        }

        MessageHeader header = message.getHeader();
        short messageId = header.getMessageId();

        logger.debug("TCP received: msgId={}, seq={}", messageId, header.getSequence());

        dispatcher.dispatch(ctx, message);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("TCP client disconnected: {}", ctx.channel().remoteAddress());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("TCP error: {}", cause.getMessage());
        ctx.close();
    }
}

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

    /**
     * @param dispatcher 同步派发解码后的业务消息
     */
    public TcpMessageHandler(MessageDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /** 新连接建立时记录对端地址。 */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("TCP client connected: {}", ctx.channel().remoteAddress());
    }

    /**
     * 校验包头后调用 {@link MessageDispatcher#dispatch}；空消息直接忽略。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
        if (message == null || message.getHeader() == null) {
            return;
        }

        MessageHeader header = message.getHeader();
        int messageId = header.getMessageId();

        logger.debug("TCP received: msgId={}, seq={}", messageId, header.getSequence());

        dispatcher.dispatch(ctx, message);
    }

    /** 连接断开时记录日志。 */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("TCP client disconnected: {}", ctx.channel().remoteAddress());
    }

    /**
     * 记录错误并关闭连接，避免半开连接占用资源。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("TCP error: {}", cause.getMessage());
        ctx.close();
    }
}

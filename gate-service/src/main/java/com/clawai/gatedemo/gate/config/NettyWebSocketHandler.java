package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.gate.handler.GateWebSocketHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketHandler.class);

    private final GateWebSocketHandler gateWebSocketHandler;

    public NettyWebSocketHandler(GateWebSocketHandler gateWebSocketHandler) {
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame) {
            String text = ((TextWebSocketFrame) frame).text();
            logger.debug("Received WebSocket message: {}", text);
            // 这里可以调用 gateWebSocketHandler 处理消息
            // 由于 Spring WebSocketHandler 和 Netty 的 API 不同，需要适配
            ctx.writeAndFlush(new TextWebSocketFrame("ACK: " + text));
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket error: {}", cause.getMessage());
        ctx.close();
    }
}
package com.clawai.gatedemo.gate.tcp.heartbeat;

import com.clawai.gatedemo.gate.protocol.MessageIdRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

public class TcpHeartbeatHandler extends IdleStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(TcpHeartbeatHandler.class);

    private static final short HEARTBEAT_MSG_ID;

    static {
        HEARTBEAT_MSG_ID = MessageIdRegistry.getIdByName("heartbeat");
    }

    public TcpHeartbeatHandler(int readerIdleTimeSeconds) {
        super(readerIdleTimeSeconds, 0, 0, TimeUnit.SECONDS);
    }

    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (evt.state() == IdleState.READER_IDLE) {
            logger.warn("TCP heartbeat timeout, closing connection: {}", ctx.channel().remoteAddress());
            ctx.close();
        }
    }
}

package com.clawai.gatedemo.gate.tcp.heartbeat;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * 在读空闲（未收到对端数据，含心跳帧）达到阈值时主动关闭连接，避免僵尸 TCP 占用网关资源。
 */
public class TcpHeartbeatHandler extends IdleStateHandler {

    private static final Logger logger = LoggerFactory.getLogger(TcpHeartbeatHandler.class);

    private static final int HEARTBEAT_MSG_ID;

    static {
        HEARTBEAT_MSG_ID = MessageRouteRegistry.getIdByName("ClientHeartbeat");
    }

    /**
     * @param readerIdleTimeSeconds 读空闲超过该秒数触发 {@link #channelIdle}
     */
    public TcpHeartbeatHandler(int readerIdleTimeSeconds) {
        super(readerIdleTimeSeconds, 0, 0, TimeUnit.SECONDS);
    }

    /**
     * 读空闲时关闭连接；其他 idle 类型交父类默认行为。
     */
    @Override
    protected void channelIdle(ChannelHandlerContext ctx, IdleStateEvent evt) throws Exception {
        if (evt.state() == IdleState.READER_IDLE) {
            logger.warn("TCP heartbeat timeout, closing connection: {}", ctx.channel().remoteAddress());
            ctx.close();
        }
    }
}

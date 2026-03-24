package com.clawai.gatedemo.gate.tcp.codec;

import com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder;
import com.clawai.gatedemo.gate.tcp.heartbeat.TcpHeartbeatHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 组装 TCP Channel 流水线：读空闲检测、心跳超时关闭、游戏帧编解码，保证连接可观测且与网关二进制协议一致。
 */
@Component
public class TcpChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final GameMessageDecoder decoder = new GameMessageDecoder();
    private final GameMessageEncoder encoder = new GameMessageEncoder();

    @Override
    protected void initChannel(SocketChannel ch) throws Exception {
        ch.pipeline()
                .addLast(new IdleStateHandler(60, 0, 0, TimeUnit.SECONDS))
                .addLast(new TcpHeartbeatHandler(60))
                .addLast(decoder)
                .addLast(encoder);
    }
}

package com.clawai.gatedemo.gate.tcp;

import com.clawai.gatedemo.gate.protocol.codec.GameMessageDecoder;
import com.clawai.gatedemo.gate.protocol.codec.GameMessageEncoder;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NettyTcpServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyTcpServer.class);

    private final int port;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyTcpServer(int port) {
        this.port = port;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new GameMessageDecoder());
                        pipeline.addLast(new GameMessageEncoder());
                        pipeline.addLast(new TcpMessageHandler(null));
                    }
                });

        ChannelFuture future = bootstrap.bind(port).sync();
        serverChannel = future.channel();
        logger.info("TCP Server started on port {}", port);
    }

    public void stop() {
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("TCP Server stopped");
    }
}

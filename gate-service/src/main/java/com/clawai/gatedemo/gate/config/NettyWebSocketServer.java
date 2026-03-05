package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.gate.handler.GateWebSocketHandler;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

@Component
public class NettyWebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(NettyWebSocketServer.class);

    @Value("${gate.port:8888}")
    private int webSocketPort;

    private final GateWebSocketHandler gateWebSocketHandler;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    public NettyWebSocketServer(GateWebSocketHandler gateWebSocketHandler) {
        this.gateWebSocketHandler = gateWebSocketHandler;
    }

    @PostConstruct
    public void start() {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new ChunkedWriteHandler());
                        pipeline.addLast(new HttpObjectAggregator(8192));
                        pipeline.addLast(new WebSocketServerProtocolHandler("/ws"));
                        pipeline.addLast(new NettyWebSocketHandler(gateWebSocketHandler));
                    }
                });

            ChannelFuture future = bootstrap.bind(webSocketPort).sync();
            serverChannel = future.channel();
            logger.info("Netty WebSocket Server started on port {}", webSocketPort);
        } catch (Exception e) {
            logger.error("Failed to start Netty WebSocket Server: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }

    @PreDestroy
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
        logger.info("Netty WebSocket Server stopped");
    }
}
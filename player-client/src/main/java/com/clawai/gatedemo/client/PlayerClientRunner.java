package com.clawai.gatedemo.client;

import com.clawai.gatedemo.client.config.PlayerClientConfig;
import com.clawai.gatedemo.client.handler.PlayerNettyHandler;
import com.clawai.gatedemo.client.ws.codec.WebSocketBinaryDecoder;
import com.clawai.gatedemo.client.ws.codec.WebSocketBinaryEncoder;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;

import java.util.concurrent.TimeUnit;

/**
 * Netty WebSocket 客户端启动器（二进制协议版）。
 * 未标注 @Component，需手动注册为 Bean 或调用 connect() 启动。
 */
public class PlayerClientRunner implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(PlayerClientRunner.class);

    private final PlayerClientConfig config;
    private final PlayerNettyHandler handler;

    public PlayerClientRunner(PlayerClientConfig config, PlayerNettyHandler handler) {
        this.config = config;
        this.handler = handler;
    }

    @Override
    public void run(String... args) {
        connect();
    }

    public void connect() {
        logger.info("=== 开始连接 Gate 服务 ===");
        logger.info("服务器：{}:{}", config.getHost(), config.getPort());

        config.setGroup(new NioEventLoopGroup());

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap
                .group(config.getGroup())
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();

                        pipeline.addLast("httpCodec", new HttpClientCodec());
                        pipeline.addLast("httpAggregator", new HttpObjectAggregator(8192));

                        WebSocketClientProtocolConfig wsConfig = WebSocketClientProtocolConfig.newBuilder()
                            .webSocketUri("ws://" + config.getHost() + ":" + config.getPort() + "/ws")
                            .build();
                        pipeline.addLast("wsProtocol", new WebSocketClientProtocolHandler(wsConfig));

                        pipeline.addLast("wsBinaryDecoder", new WebSocketBinaryDecoder());
                        pipeline.addLast("wsBinaryEncoder", new WebSocketBinaryEncoder());
                        pipeline.addLast("idleState", new IdleStateHandler(0, config.getHeartbeatInterval(), 0, TimeUnit.SECONDS));
                        pipeline.addLast("businessHandler", handler);
                    }
                });

            ChannelFuture future = bootstrap.connect(config.getHost(), config.getPort()).sync();
            config.setChannel(future.channel());
            logger.info("已连接到 Gate 服务！");

            config.getChannel().closeFuture().sync();

        } catch (Exception e) {
            logger.error("连接失败：{}", e.getMessage());
        } finally {
            if (config.getGroup() != null) {
                config.getGroup().shutdownGracefully();
            }
            logger.info("客户端已关闭");
        }
    }
}

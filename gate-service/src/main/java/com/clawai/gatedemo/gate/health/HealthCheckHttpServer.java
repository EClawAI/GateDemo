package com.clawai.gatedemo.gate.health;

import com.clawai.gatedemo.gate.config.GateConfig;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 轻量 Netty HTTP 服务：提供 {@code /health}、{@code /ready} 探活及 {@code /metrics} Prometheus 抓取。
 */
@Component
public class HealthCheckHttpServer {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckHttpServer.class);

    private final GateConfig gateConfig;
    private final RedisTemplate<String, Object> redisTemplate;
    private final GameGrpcClientPool grpcPool;
    private final ObjectMapper objectMapper;
    private final PrometheusMeterRegistry prometheusMeterRegistry;

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private Channel channel;

    /**
     * @param gateConfig              健康检查监听端口等
     * @param redisTemplate           探活 Redis
     * @param grpcPool                探活 gRPC 连接池是否非空
     * @param objectMapper            健康 JSON 序列化
     * @param prometheusMeterRegistry {@code /metrics} 文本输出
     */
    public HealthCheckHttpServer(GateConfig gateConfig, RedisTemplate<String, Object> redisTemplate,
                                  GameGrpcClientPool grpcPool, ObjectMapper objectMapper,
                                  PrometheusMeterRegistry prometheusMeterRegistry) {
        this.gateConfig = gateConfig;
        this.redisTemplate = redisTemplate;
        this.grpcPool = grpcPool;
        this.objectMapper = objectMapper;
        this.prometheusMeterRegistry = prometheusMeterRegistry;
    }

    /**
     * 绑定健康端口；启动失败会中断当前线程标志位并调用 {@link #shutdown()}。
     */
    @PostConstruct
    public void start() {
        int port = gateConfig.getHealth().getPort();
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(2);

        try {
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(8192));
                            p.addLast(new HealthCheckHandler(prometheusMeterRegistry));
                        }
                    });

            ChannelFuture future = bootstrap.bind(port).sync();
            channel = future.channel();
            logger.info("Health check HTTP server started on port {}", port);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Failed to start health check server", e);
            shutdown();
        }
    }

    /**
     * Spring 销毁阶段关闭 Channel 与线程组。
     */
    @PreDestroy
    public void stop() {
        shutdown();
    }

    /** 释放监听与 EventLoop 资源，可重复调用。 */
    private void shutdown() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
            bossGroup = null;
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
            workerGroup = null;
        }
        logger.info("Health check HTTP server stopped");
    }

    /**
     * @return {@code status} 与 {@code components}（redis、grpcPool、grpcConnections）；全部 UP 时整体为 UP
     */
    private Map<String, Object> computeHealth() {
        Map<String, Object> components = new HashMap<>();
        String redisStatus = checkRedis();
        String grpcPoolStatus = checkGrpcPool();
        int grpcConnections = grpcPool.getPoolSize();

        components.put("redis", redisStatus);
        components.put("grpcPool", grpcPoolStatus);
        components.put("grpcConnections", grpcConnections);

        boolean allUp = "UP".equals(redisStatus) && "UP".equals(grpcPoolStatus);
        String status = allUp ? "UP" : "DOWN";

        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("components", components);
        return result;
    }

    /** @return {@code UP} / {@code DOWN}，异常时记 debug 日志 */
    private String checkRedis() {
        try {
            RedisConnection conn = redisTemplate.getConnectionFactory().getConnection();
            try {
                conn.ping();
                return "UP";
            } finally {
                conn.close();
            }
        } catch (Exception e) {
            logger.debug("Redis health check failed: {}", e.getMessage());
            return "DOWN";
        }
    }

    /** 以池中是否存在至少一条连接作为粗略就绪信号。 */
    private String checkGrpcPool() {
        return grpcPool.getPoolSize() > 0 ? "UP" : "DOWN";
    }

    private class HealthCheckHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        private final PrometheusMeterRegistry prometheusMeterRegistry;

        HealthCheckHandler(PrometheusMeterRegistry prometheusMeterRegistry) {
            this.prometheusMeterRegistry = prometheusMeterRegistry;
        }

        /**
         * 路由 {@code /metrics} 返回 Prometheus 文本；{@code /ready} 依赖聚合健康；{@code /health} 恒 200 但 body 含组件状态。
         */
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) throws Exception {
            String path = req.uri().split("\\?")[0];

            if ("/metrics".equals(path) || "/metrics/".equals(path)) {
                String scrape = prometheusMeterRegistry.scrape();
                byte[] body = scrape.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; version=0.0.4; charset=utf-8");
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
                response.content().writeBytes(body);
                ctx.writeAndFlush(response);
                return;
            }

            Map<String, Object> health = computeHealth();
            boolean ready = "UP".equals(health.get("status"));

            HttpResponseStatus status;
            if ("/ready".equals(path) || "/ready/".equals(path)) {
                status = ready ? HttpResponseStatus.OK : HttpResponseStatus.SERVICE_UNAVAILABLE;
            } else if ("/health".equals(path) || "/health/".equals(path)) {
                status = HttpResponseStatus.OK;
            } else {
                ctx.writeAndFlush(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND));
                return;
            }

            byte[] body = objectMapper.writeValueAsBytes(health);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.content().writeBytes(body);

            ctx.writeAndFlush(response);
        }
    }
}

package com.clawai.gatedemo.gate.router;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将解码后的 {@link WrappedMessage} 按消息号派发到已注册处理器；内置鉴权与心跳占位处理，业务 handler 可运行时挂载。
 */
@Component
public class MessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MessageDispatcher.class);

    private final HandlerRegistry registry;
    /** 异步分发使用的固定大小线程池（守护线程） */
    private final ExecutorService executor;

    /** 创建注册表、线程池并注册默认鉴权/心跳占位处理器。 */
    public MessageDispatcher() {
        this.registry = new HandlerRegistry();
        this.executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "message-dispatcher");
            t.setDaemon(true);
            return t;
        });
        registerDefaultHandlers();
    }

    private void registerDefaultHandlers() {
        register((short) 0x1001, new AuthHandler());
        register((short) 0x2001, new HeartbeatHandler());
    }

    /**
     * @param messageId 消息号
     * @param handler   业务处理器
     */
    public void register(short messageId, MessageHandler handler) {
        registry.register(messageId, handler);
        logger.info("Registered handler for messageId: {}", messageId);
    }

    /**
     * 在当前线程同步派发：先 {@link MessageHandler#shouldHandle(WrappedMessage)}，再 {@link MessageHandler#handle(io.netty.channel.ChannelHandlerContext, WrappedMessage)}；异常记日志不向外抛。
     */
    public void dispatch(ChannelHandlerContext ctx, WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return;
        }

        short messageId = message.getHeader().getMessageId();
        MessageHandler handler = registry.getHandler(messageId);

        if (handler == null) {
            logger.warn("No handler for messageId: {}", messageId);
            return;
        }

        try {
            if (handler.shouldHandle(message)) {
                handler.handle(ctx, message);
            }
        } catch (Exception e) {
            logger.error("Error handling message {}: {}", messageId, e.getMessage(), e);
        }
    }

    /** 将 {@link #dispatch} 提交到线程池，避免阻塞 Netty IO 线程。 */
    public void dispatchAsync(ChannelHandlerContext ctx, WrappedMessage message) {
        executor.execute(() -> dispatch(ctx, message));
    }

    /** @return 内部使用的注册表，可供外部继续 register */
    public HandlerRegistry getRegistry() {
        return registry;
    }

    private static class AuthHandler implements MessageHandler {
        private static final Logger log = LoggerFactory.getLogger(AuthHandler.class);

        @Override
        public void handle(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
            log.info("Auth handler: {}", message);
        }
    }

    private static class HeartbeatHandler implements MessageHandler {
        private static final Logger log = LoggerFactory.getLogger(HeartbeatHandler.class);

        @Override
        public void handle(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
            log.info("Heartbeat handler: {}", message);
        }
    }
}

package com.clawai.gatedemo.gate.router;

import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class MessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(MessageDispatcher.class);

    private final HandlerRegistry registry;
    private final ExecutorService executor;

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

    public void register(short messageId, MessageHandler handler) {
        registry.register(messageId, handler);
        logger.info("Registered handler for messageId: {}", messageId);
    }

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

    public void dispatchAsync(ChannelHandlerContext ctx, WrappedMessage message) {
        executor.execute(() -> dispatch(ctx, message));
    }

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

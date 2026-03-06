package com.clawai.gatedemo.gate.log;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageLogger {

    private static final Logger logger = LoggerFactory.getLogger("MessageLogger");

    public static void logIncoming(String traceId, Long playerId, WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return;
        }

        String messageName = getMessageName(message.getHeader().getMessageId());
        logger.info("[{}] IN  playerId={} msgId={}({}) seq={} len={}",
                traceId, playerId,
                message.getHeader().getMessageId(),
                messageName,
                message.getHeader().getSequence(),
                message.getHeader().getBodyLength());
    }

    public static void logOutgoing(String traceId, Long playerId, WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return;
        }

        String messageName = getMessageName(message.getHeader().getMessageId());
        logger.info("[{}] OUT playerId={} msgId={}({}) seq={} len={}",
                traceId, playerId,
                message.getHeader().getMessageId(),
                messageName,
                message.getHeader().getSequence(),
                message.getHeader().getBodyLength());
    }

    public static void logError(String traceId, Long playerId, String error) {
        logger.error("[{}] ERROR playerId={} msg={}", traceId, playerId, error);
    }

    private static String getMessageName(short messageId) {
        try {
            Class<?> registryClass = Class.forName("com.clawai.gatedemo.gate.protocol.MessageIdRegistry");
            java.lang.reflect.Method method = registryClass.getMethod("getNameById", short.class);
            String name = (String) method.invoke(null, messageId);
            return name != null ? name : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }
}

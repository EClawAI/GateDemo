package com.clawai.gatedemo.gate.security;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 对 {@link WrappedMessage} 做入站校验：messageId 注册检查、body 体积限制。
 */
@Component
public class MessageValidator {

    private static final Logger logger = LoggerFactory.getLogger(MessageValidator.class);

    private final int bodyMaxBytes;

    public MessageValidator(@Value("${gate.message.max-length:65536}") int bodyMaxBytes) {
        this.bodyMaxBytes = bodyMaxBytes;
    }

    /**
     * @param message 待校验消息
     * @return 合法返回 null，否则为简短错误描述
     */
    public String validate(WrappedMessage message) {
        if (message == null || message.getHeader() == null) {
            return "Message is null";
        }

        int messageId = message.getHeader().getMessageId();

        if (MessageRouteRegistry.getByMsgId(messageId) == null) {
            return "Unknown messageId: " + messageId;
        }

        int bodyLength = message.getHeader().getBodyLength();
        if (bodyLength > bodyMaxBytes) {
            return "Body exceeds max size " + bodyMaxBytes + " bytes";
        }

        return null;
    }
}

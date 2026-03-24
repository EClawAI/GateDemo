package com.clawai.gatedemo.gate.security;

import com.clawai.gatedemo.gate.model.PlayerMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 对 {@link PlayerMessage} 做入站校验：type 白名单、game_msg 必填字段、msgType 长度与 body 序列化后体积。
 */
@Component
public class MessageValidator {

    private static final Logger logger = LoggerFactory.getLogger(MessageValidator.class);

    private static final Set<String> ALLOWED_TYPES = Set.of("auth", "heartbeat", "game_msg");
    private static final int DEFAULT_MSG_TYPE_MAX_LENGTH = 64;
    private static final int DEFAULT_BODY_MAX_BYTES = 64 * 1024; // 64KB

    private final ObjectMapper objectMapper;
    private final int msgTypeMaxLength;
    private final int bodyMaxBytes;

    /**
     * @param objectMapper   将 body 序列化为字节以估算大小
     * @param msgTypeMaxLength {@code gate.message.msg-type-max-length}
     * @param bodyMaxBytes     {@code gate.message.max-length}，单条 body 上限（字节）
     */
    public MessageValidator(ObjectMapper objectMapper,
                            @Value("${gate.message.msg-type-max-length:64}") int msgTypeMaxLength,
                            @Value("${gate.message.max-length:65536}") int bodyMaxBytes) {
        this.objectMapper = objectMapper;
        this.msgTypeMaxLength = msgTypeMaxLength;
        this.bodyMaxBytes = bodyMaxBytes;
    }

    /**
     * @param message 待校验消息
     * @return 合法返回 null，否则为简短错误描述（与实现返回文案一致，便于日志或回包）
     */
    public String validate(PlayerMessage message) {
        if (message == null) {
            return "Message is null";
        }

        String type = message.getType();
        if (type == null || type.isEmpty()) {
            return "Message type is required";
        }
        if (!ALLOWED_TYPES.contains(type)) {
            return "Invalid message type: " + type;
        }

        if ("game_msg".equals(type)) {
            Long playerId = message.getPlayerId();
            if (playerId == null || playerId <= 0) {
                return "game_msg requires playerId > 0";
            }
            Integer gameId = message.getGameId();
            if (gameId == null || gameId <= 0) {
                return "game_msg requires gameId > 0";
            }
        }

        String msgType = message.getMsgType();
        if (msgType != null && msgType.length() > msgTypeMaxLength) {
            return "msgType exceeds max length " + msgTypeMaxLength;
        }

        Map<String, Object> body = message.getBody();
        if (body != null && !body.isEmpty()) {
            try {
                byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
                if (bodyBytes.length > bodyMaxBytes) {
                    return "Body exceeds max size " + bodyMaxBytes + " bytes";
                }
            } catch (JsonProcessingException e) {
                logger.warn("Failed to serialize body for size check: {}", e.getMessage());
                return "Invalid body format";
            }
        }

        return null;
    }
}

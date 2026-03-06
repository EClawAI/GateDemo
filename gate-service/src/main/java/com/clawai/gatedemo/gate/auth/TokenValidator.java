package com.clawai.gatedemo.gate.auth;

import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TokenValidator {

    private static final Logger logger = LoggerFactory.getLogger(TokenValidator.class);

    private final TokenService tokenService;

    public TokenValidator(TokenService tokenService) {
        this.tokenService = tokenService;
    }

    public boolean validate(ChannelHandlerContext ctx, WrappedMessage message) {
        if (message == null || message.getBody() == null) {
            return false;
        }

        String token = getTokenFromMessage(message);
        if (token == null || token.isEmpty()) {
            logger.warn("No token in message from {}", ctx.channel().remoteAddress());
            return false;
        }

        boolean valid = tokenService.validateToken(token);
        if (!valid) {
            logger.warn("Invalid token from {}", ctx.channel().remoteAddress());
        }

        return valid;
    }

    public Long getPlayerId(ChannelHandlerContext ctx, WrappedMessage message) {
        if (message == null || message.getBody() == null) {
            return null;
        }

        String token = getTokenFromMessage(message);
        if (token != null) {
            return tokenService.getPlayerId(token);
        }

        return null;
    }

    private String getTokenFromMessage(WrappedMessage message) {
        try {
            if (message.getBody() != null && message.getBody().getData() != null) {
                Object token = message.getBody().getData().get("token");
                return token != null ? token.toString() : null;
            }
        } catch (Exception e) {
            logger.error("Error getting token from message: {}", e.getMessage());
        }
        return null;
    }
}

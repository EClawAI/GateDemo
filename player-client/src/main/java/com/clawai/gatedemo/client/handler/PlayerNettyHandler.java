package com.clawai.gatedemo.client.handler;

import com.clawai.gatedemo.client.config.PlayerClientConfig;
import com.clawai.gatedemo.client.protocol.model.MessageHeader;
import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.client.protocol.model.RawMessageBody;
import com.clawai.gatedemo.client.protocol.model.WrappedMessage;
import com.clawai.gatedemo.proto.gate.AuthRequest;
import com.clawai.gatedemo.proto.gate.AuthResponse;
import com.clawai.gatedemo.proto.gate.ClientHeartbeat;
import com.clawai.gatedemo.proto.gate.HeartbeatAck;
import com.clawai.gatedemo.proto.game.CgBattleMove;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.WebSocketClientProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Netty WebSocket 客户端业务处理器（二进制协议版）。
 * 接收 {@link WrappedMessage}（由 WebSocket Binary Codec 解码），按 messageId 处理。
 */
@Component
public class PlayerNettyHandler extends SimpleChannelInboundHandler<WrappedMessage> {

    private static final Logger logger = LoggerFactory.getLogger(PlayerNettyHandler.class);

    private final PlayerClientConfig config;
    private boolean connected = false;

    public PlayerNettyHandler(PlayerClientConfig config) {
        this.config = config;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("已连接到服务器：{}:{}", config.getHost(), config.getPort());
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketClientProtocolHandler.ClientHandshakeStateEvent event) {
            if (event == WebSocketClientProtocolHandler.ClientHandshakeStateEvent.HANDSHAKE_COMPLETE) {
                logger.info("WebSocket 握手完成");
                connected = true;
                sendAuth(ctx);
            }
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WrappedMessage message) throws Exception {
        int messageId = message.getHeader().getMessageId();
        byte[] bodyBytes = message.getBody() != null ? message.getBody().toBytes() : new byte[0];

        if (messageId == MessageRouteRegistry.getIdByName("AuthRequest")) {
            AuthResponse resp = AuthResponse.parseFrom(bodyBytes);
            if (resp.getSuccess()) {
                logger.info("认证成功！玩家 ID: {}", resp.getPlayerId());
            } else {
                logger.warn("认证失败: {}", resp.getMessage());
            }
        } else if (messageId == MessageRouteRegistry.getIdByName("HeartbeatAck")) {
            HeartbeatAck ack = HeartbeatAck.parseFrom(bodyBytes);
            logger.debug("心跳响应: serverTime={}", ack.getServerTime());
        } else {
            logger.info("收到消息: msgId=0x{}, bodyLen={}",
                    Integer.toHexString(messageId & 0xFFFF), bodyBytes.length);
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("连接已断开");
        connected = false;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.error("连接异常：{}", cause.getMessage());
        ctx.close();
    }

    private void sendAuth(ChannelHandlerContext ctx) {
        String token = config.getAuthToken();
        if (token == null || token.isBlank()) {
            logger.error("未配置 player.auth-token / PLAYER_JWT，无法认证");
            ctx.close();
            return;
        }
        AuthRequest authReq = AuthRequest.newBuilder()
                .setToken(token)
                .setGameId(config.getGameId())
                .build();

        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(MessageRouteRegistry.getIdByName("AuthRequest"));
        msg.getHeader().setMode(MessageHeader.MODE_REQUEST);
        msg.setBody(new RawMessageBody(authReq.toByteArray()));

        ctx.writeAndFlush(msg);
        logger.info("发送认证消息：playerId={}", config.getPlayerId());
    }

    public void sendHeartbeat(ChannelHandlerContext ctx) {
        if (!connected) return;

        ClientHeartbeat hb = ClientHeartbeat.newBuilder()
                .setTimestamp(System.currentTimeMillis()).build();

        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(MessageRouteRegistry.getIdByName("ClientHeartbeat"));
        msg.setBody(new RawMessageBody(hb.toByteArray()));

        ctx.writeAndFlush(msg);
        logger.debug("发送心跳");
    }

    public void sendGameMessage(ChannelHandlerContext ctx, int x, int y) {
        if (!connected) return;

        CgBattleMove move = CgBattleMove.newBuilder().setX(x).setY(y).build();

        WrappedMessage msg = new WrappedMessage();
        msg.getHeader().setMessageId(MessageRouteRegistry.getIdByName("CgBattleMove"));
        msg.setBody(new RawMessageBody(move.toByteArray()));

        ctx.writeAndFlush(msg);
        logger.info("发送游戏消息: battle.move x={}, y={}", x, y);
    }
}

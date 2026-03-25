package com.clawai.gatedemo.gate.route;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Game 服务路由器：从 Channel Attribute 读取 gameId，通过 gRPC 连接池将原始消息体转发给 game-service。
 */
@Component
public class GameServiceRouter implements ServiceRouter {

    private static final Logger logger = LoggerFactory.getLogger(GameServiceRouter.class);

    public static final AttributeKey<Integer> GAME_ID_KEY = AttributeKey.valueOf("gameId");

    private final GameGrpcClientPool pool;

    public GameServiceRouter(GameGrpcClientPool pool) {
        this.pool = pool;
    }

    @Override
    public String serviceType() {
        return "game";
    }

    @Override
    public void forward(ChannelHandlerContext ctx, WrappedMessage message, Long playerId) {
        Integer gameId = ctx.channel().attr(GAME_ID_KEY).get();
        if (gameId == null) {
            logger.warn("玩家 {} 未绑定 gameId，无法转发消息", playerId);
            return;
        }

        int messageId = message.getHeader().getMessageId();
        MessageRouteRegistry.RouteInfo route = MessageRouteRegistry.getByMsgId(messageId);
        String msgType = route != null ? route.name() : "unknown";
        byte[] rawBody = message.getBody() != null ? message.getBody().toBytes() : new byte[0];
        int seq = message.getHeader().getSequence();

        boolean success = pool.sendGameMessageViaStream(gameId, playerId, msgType, seq, rawBody);
        if (success) {
            logger.debug("消息已转发到 Game: gameId={}, playerId={}, msgType={}", gameId, playerId, msgType);
        } else {
            logger.error("消息转发失败: gameId={}, playerId={}, msgType={}", gameId, playerId, msgType);
        }
    }
}

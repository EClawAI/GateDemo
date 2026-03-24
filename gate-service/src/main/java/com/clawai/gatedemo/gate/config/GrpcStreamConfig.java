package com.clawai.gatedemo.gate.config;

import com.clawai.gatedemo.common.route.MessageRouteRegistry;
import com.clawai.gatedemo.gate.grpc.GameGrpcClientPool;
import com.clawai.gatedemo.gate.protocol.model.MessageHeader;
import com.clawai.gatedemo.gate.protocol.model.RawMessageBody;
import com.clawai.gatedemo.gate.protocol.model.WrappedMessage;
import com.clawai.gatedemo.gate.service.PlayerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * 配置 gRPC 流式回调：将 Game 经双向流回推的消息转为 {@link WrappedMessage} 并下发给玩家。
 */
@Configuration
public class GrpcStreamConfig {

    private static final Logger logger = LoggerFactory.getLogger(GrpcStreamConfig.class);

    private final GameGrpcClientPool pool;
    private final PlayerService playerService;

    public GrpcStreamConfig(GameGrpcClientPool pool, PlayerService playerService) {
        this.pool = pool;
        this.playerService = playerService;
    }

    @PostConstruct
    public void init() {
        pool.setStreamMessageHandler(message -> {
            long playerId = message.getPlayerId();
            String msgType = message.getMsgType();
            byte[] bodyBytes = message.getBody().toByteArray();

            short messageId = MessageRouteRegistry.getIdByName(msgType);

            WrappedMessage wm = new WrappedMessage();
            wm.getHeader().setMessageId(messageId);
            wm.getHeader().setMode(MessageHeader.MODE_PUSH);
            wm.setBody(new RawMessageBody(bodyBytes));

            if (playerId > 0) {
                playerService.sendToPlayer(playerId, wm);
                logger.debug("下行消息发送给玩家: playerId={}, msgType={}", playerId, msgType);
            } else {
                for (Long pid : playerService.getAllOnlinePlayerIds()) {
                    playerService.sendToPlayer(pid, wm);
                }
                logger.debug("广播消息发送给 {} 个玩家: msgType={}", playerService.getOnlineCount(), msgType);
            }
        });
    }
}

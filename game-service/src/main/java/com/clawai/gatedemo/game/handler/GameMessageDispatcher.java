package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.core.message.MessageHandlerRegistry;
import com.clawai.gatedemo.core.message.MessageSender;
import com.clawai.gatedemo.game.model.PlayerData;
import com.clawai.gatedemo.game.persistence.PlayerDataManager;
import com.google.protobuf.MessageLite;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Game 服务消息分发器：编排 前置（加载 player）→ 分发（查 handler + parseFrom）→ 后置（dirty 存盘）。
 */
@Component
public class GameMessageDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(GameMessageDispatcher.class);

    private final MessageHandlerRegistry registry;
    private final PlayerDataManager playerDataManager;

    /** 下行发送器，由 gRPC stream 建立时注入。 */
    private volatile MessageSender sender;

    public GameMessageDispatcher(MessageHandlerRegistry registry,
                                  PlayerDataManager playerDataManager) {
        this.registry = registry;
        this.playerDataManager = playerDataManager;
    }

    public void setSender(MessageSender sender) {
        this.sender = sender;
    }

    /**
     * 分发一条消息。由 gRPC 入口调用。
     *
     * @param playerId  玩家 ID
     * @param gameId    游戏实例 ID
     * @param messageId 消息 ID（CRC32）
     * @param seq       客户端序列号
     * @param body      protobuf 二进制 body
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void dispatch(long playerId, int gameId, int messageId, int seq, byte[] body) {
        MessageHandlerRegistry.HandlerEntry entry = registry.getHandler(messageId);
        if (entry == null) {
            logger.warn("未注册的 messageId={}", messageId);
            return;
        }

        try {
            // 前置：构建上下文，加载 Player
            GameMessageContext ctx = new GameMessageContext();
            ctx.setPlayerId(playerId);
            ctx.setGameId(gameId);
            ctx.setMessageId(messageId);
            ctx.setSequence(seq);
            ctx.setSender(sender);

            PlayerData player = playerDataManager.load(playerId);
            ctx.setPlayer(player);

            // 反序列化
            MessageLite msg = (MessageLite) entry.parser().parseFrom(body);

            // 分发
            ((com.clawai.gatedemo.core.message.IMessageHandler) entry.handler()).handle(ctx, msg);

            // 后置：dirty 检查，触发立即存盘
            if (ctx.isDirty()) {
                playerDataManager.saveNow(playerId);
            }

        } catch (Exception e) {
            logger.error("消息处理异常: messageId={}, player={}, error={}",
                    messageId, playerId, e.getMessage(), e);
        }
    }
}

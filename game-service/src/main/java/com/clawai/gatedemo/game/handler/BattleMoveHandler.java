package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.core.message.MessageMapping;
import com.clawai.gatedemo.proto.game.CgBattleMove;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 战斗移动消息处理器。
 */
@MessageMapping(CgBattleMove.class)
@Component
public class BattleMoveHandler implements IGameHandler<CgBattleMove> {

    private static final Logger logger = LoggerFactory.getLogger(BattleMoveHandler.class);

    @Override
    public void handle(GameMessageContext ctx, CgBattleMove msg) throws Exception {
        logger.info("战斗移动: playerId={}, gameId={}, x={}, y={}",
                ctx.getPlayerId(), ctx.getGameId(), msg.getX(), msg.getY());
    }
}

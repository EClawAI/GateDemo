package com.clawai.gatedemo.game.handler;

import com.clawai.gatedemo.core.message.MessageMapping;
import com.clawai.gatedemo.proto.game.CgBattleAttack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 战斗攻击消息处理器。
 */
@MessageMapping(CgBattleAttack.class)
@Component
public class BattleAttackHandler implements IGameHandler<CgBattleAttack> {

    private static final Logger logger = LoggerFactory.getLogger(BattleAttackHandler.class);

    @Override
    public void handle(GameMessageContext ctx, CgBattleAttack msg) throws Exception {
        logger.info("战斗攻击: playerId={}, gameId={}, targetId={}, skillId={}",
                ctx.getPlayerId(), ctx.getGameId(), msg.getTargetId(), msg.getSkillId());
    }
}

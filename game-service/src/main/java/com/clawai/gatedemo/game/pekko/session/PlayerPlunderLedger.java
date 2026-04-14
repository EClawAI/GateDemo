package com.clawai.gatedemo.game.pekko.session;

/**
 * 玩家掠夺结算账本：权威状态在 {@link com.clawai.gatedemo.game.model.PlayerData}（Mongo），
 * 由 {@link com.clawai.gatedemo.game.persistence.MongoPlayerPlunderLedger} 等实现。
 */
public interface PlayerPlunderLedger {

    /**
     * 按 {@code battleId} 幂等扣款并返回结果；与 {@link PlayerSessionBehavior.SettlePlunder} 一一对应。
     *
     * @param playerId        受害玩家
     * @param battleId        战斗/结算幂等键
     * @param requestedPlunder 请求掠夺量（正数）
     */
    PlunderSettleResponse trySettle(long playerId, long battleId, long requestedPlunder);
}

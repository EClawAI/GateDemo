package com.clawai.gatedemo.game.pekko.session;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 测试用内存账本：不访问 Mongo，行为与生产路径的幂等/扣款规则一致。
 */
public final class InMemoryPlayerPlunderLedger implements PlayerPlunderLedger {

    private final ConcurrentHashMap<Long, Long> battleToActual = new ConcurrentHashMap<>();
    private volatile long walletGold = 10_000L;

    @Override
    public PlunderSettleResponse trySettle(long playerId, long battleId, long requestedPlunder) {
        Long prior = battleToActual.get(battleId);
        if (prior != null) {
            return new PlunderDuplicate(battleId, prior);
        }
        if (requestedPlunder <= 0) {
            return new PlunderRejected(battleId, "requestedPlunder 必须为正数");
        }
        long actual = Math.min(requestedPlunder, walletGold);
        walletGold -= actual;
        battleToActual.put(battleId, actual);
        return new PlunderOk(battleId, actual);
    }
}

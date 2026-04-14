package com.clawai.gatedemo.game.model;

/**
 * Mongo 嵌套文档：单次掠夺结算的幂等记录（同一 {@code battleId} 至多一条）。
 */
public class PlunderSettlementRecord {

    private long battleId;
    private long actualAmount;

    public PlunderSettlementRecord() {}

    public PlunderSettlementRecord(long battleId, long actualAmount) {
        this.battleId = battleId;
        this.actualAmount = actualAmount;
    }

    public long getBattleId() {
        return battleId;
    }

    public void setBattleId(long battleId) {
        this.battleId = battleId;
    }

    public long getActualAmount() {
        return actualAmount;
    }

    public void setActualAmount(long actualAmount) {
        this.actualAmount = actualAmount;
    }
}

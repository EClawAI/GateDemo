package com.clawai.gatedemo.game.pekko.session;

/** 首次成功结算：返回本次实扣数量。 */
public record PlunderOk(long battleId, long actualAmount) implements PlunderSettleResponse {}

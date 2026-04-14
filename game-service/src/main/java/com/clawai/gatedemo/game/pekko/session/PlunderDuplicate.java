package com.clawai.gatedemo.game.pekko.session;

/** 同一 battleId 重放：返回此前已提交的实扣数量（不重复扣款）。 */
public record PlunderDuplicate(long battleId, long actualAmount) implements PlunderSettleResponse {}

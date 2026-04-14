package com.clawai.gatedemo.game.pekko.session;

/** 拒绝结算（参数非法等），携带原因说明。 */
public record PlunderRejected(long battleId, String reason) implements PlunderSettleResponse {}

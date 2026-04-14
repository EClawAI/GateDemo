package com.clawai.gatedemo.game.pekko.session;

public record PlunderOk(long battleId, long actualAmount) implements PlunderSettleResponse {}

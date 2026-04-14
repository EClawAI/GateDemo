package com.clawai.gatedemo.game.pekko.session;

public record PlunderRejected(long battleId, String reason) implements PlunderSettleResponse {}

package com.clawai.gatedemo.game.pekko.session;

/** Same battleId replay: return previously committed actual (no double spend). */
public record PlunderDuplicate(long battleId, long actualAmount) implements PlunderSettleResponse {}

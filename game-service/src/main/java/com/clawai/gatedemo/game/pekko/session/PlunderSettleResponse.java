package com.clawai.gatedemo.game.pekko.session;

/** Reply from {@link PlayerSessionBehavior} for {@link PlayerSessionBehavior.SettlePlunder}. */
public sealed interface PlunderSettleResponse permits PlunderOk, PlunderRejected, PlunderDuplicate {

    long battleId();
}

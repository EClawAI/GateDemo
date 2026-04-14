package com.clawai.gatedemo.game.pekko.session;

/** {@link PlayerSessionBehavior.SettlePlunder} 的回复类型（密封接口）。 */
public sealed interface PlunderSettleResponse permits PlunderOk, PlunderRejected, PlunderDuplicate {

    long battleId();
}

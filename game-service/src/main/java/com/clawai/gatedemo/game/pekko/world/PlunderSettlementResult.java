package com.clawai.gatedemo.game.pekko.world;

/** {@link WorldMapSandboxBehavior.SettlePlunderVictim} 的结算结果，交付给原始调用方。 */
public sealed interface PlunderSettlementResult permits PlunderSettlementResult.Ok, PlunderSettlementResult.Failed {

    record Ok(long battleId, long actualPlunder) implements PlunderSettlementResult {}

    record Failed(String reason) implements PlunderSettlementResult {}
}

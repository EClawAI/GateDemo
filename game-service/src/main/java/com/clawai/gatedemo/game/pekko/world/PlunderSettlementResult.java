package com.clawai.gatedemo.game.pekko.world;

/** Outcome of {@link CityBehavior.SettlePlunderVictim} delivered to the original caller. */
public sealed interface PlunderSettlementResult permits PlunderSettlementResult.Ok, PlunderSettlementResult.Failed {

    record Ok(long battleId, long actualPlunder) implements PlunderSettlementResult {}

    record Failed(String reason) implements PlunderSettlementResult {}
}

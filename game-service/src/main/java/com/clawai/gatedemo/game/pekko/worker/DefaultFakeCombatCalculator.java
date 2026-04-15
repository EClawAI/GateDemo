package com.clawai.gatedemo.game.pekko.worker;

import org.springframework.stereotype.Component;

/** 确定性伪随机示例，便于单测断言。 */
@Component
public final class DefaultFakeCombatCalculator implements FakeCombatCalculator {

    @Override
    public FakeCombatOutcome compute(FakeCombatInput input) {
        long mix = input.battleId() ^ input.randomSeed();
        boolean win = (mix & 1L) == 0L;
        int damage = (int) (Math.abs(mix) % 1000);
        return new FakeCombatOutcome(input.battleId(), win, damage);
    }
}

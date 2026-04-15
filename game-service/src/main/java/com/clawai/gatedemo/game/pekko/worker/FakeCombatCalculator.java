package com.clawai.gatedemo.game.pekko.worker;

/**
 * 无状态战斗结算（示例）：不持有玩家/沙盘引用，仅根据输入快照计算结构化结果。
 *
 * <p>实现类不得缓存可变权威状态；并发安全由「不共享可变单例」保证。
 */
@FunctionalInterface
public interface FakeCombatCalculator {

    FakeCombatOutcome compute(FakeCombatInput input);
}

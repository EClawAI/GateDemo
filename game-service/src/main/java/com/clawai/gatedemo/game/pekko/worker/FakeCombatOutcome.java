package com.clawai.gatedemo.game.pekko.worker;

/** Worker 计算结果 DTO；由沙盘邮箱在下一跳消息中应用。 */
public record FakeCombatOutcome(long battleId, boolean attackerWins, int damage) {}

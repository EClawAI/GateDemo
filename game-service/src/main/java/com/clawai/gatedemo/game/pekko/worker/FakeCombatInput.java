package com.clawai.gatedemo.game.pekko.worker;

/** 传入 Worker 纯函数的只读输入（无权威 Actor 引用）。 */
public record FakeCombatInput(long battleId, long randomSeed) {}

package com.clawai.gatedemo.gate.flow;

/**
 * Redis 中 {@code gate:flow:<flowId>} Hash 的内存投影，对应 design.md §5 字段集合。
 *
 * <p>所有时间字段使用 epoch 毫秒；{@code detachedAt = 0} 表示 ATTACHED；{@code lastSeqAnchor}
 * 在 Phase A 仅记录最近一次客户端 ACK 游标，不驱动重放。
 *
 * @param flowId        独立 flow 标识（UUID v4）
 * @param playerId      绑定玩家 ID
 * @param gameId        绑定 game 实例 ID（用于 gRPC 池）
 * @param ownerGateId   持有该 flow 的 gate 实例 ID；跨实例 RESUME 在 Phase A 被拒
 * @param createdAt     flow 创建时间（epoch ms）
 * @param expiresAt     flow 过期时间（epoch ms），低于 now 视为失效
 * @param detachedAt    最近一次 Detach 时间（epoch ms）；0 表示 ATTACHED
 * @param lastSeqAnchor 客户端 ACK 的最后一个下行 seq 锚点
 */
public record RedisFlowRecord(
        String flowId,
        long playerId,
        int gameId,
        String ownerGateId,
        long createdAt,
        long expiresAt,
        long detachedAt,
        long lastSeqAnchor) {
}

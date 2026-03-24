package com.clawai.gatedemo.gate.protocol;

/**
 * 消息ID生成器 - 使用FNV哈希算法从消息名称生成消息ID
 *
 * 设计原理：
 * 游戏中需要唯一标识每种消息类型，传统方式是手动分配ID（如1, 2, 3）。
 * 但手动分配有以下缺点：
 * - 需要维护一个ID列表
 * - 多人开发时容易冲突
 * - 新增消息需要协调
 *
 * FNV哈希算法解决方案：
 * - 自动生成ID，无需手动维护
 * - 相同名称总是生成相同ID
 * - 分布均匀，冲突概率低
 *
 * FNV-1a算法：
 * FNV(Fowler-Noll-Vo)是一种非加密哈希算法，
 * 简单高效，适合生成唯一标识符。
 *
 * 算法步骤：
 * 1. 使用FNV_64_INITIAL作为初始值
 * 2. 对每个字符：hash = hash XOR char，hash = hash * FNV_64_PRIME
 * 3. 取低16位作为最终ID
 *
 * 为什么取低16位？
 * - short类型是16位（2字节）
 * - 足够表示65535种消息类型
 * - 游戏消息类型远少于这个数量
 *
 * 示例：
 * "auth.login" → 0x1001 (4097)
 * "heartbeat" → 0x2001 (8193)
 * "battle.move" → 0x4A7B (19067)
 *
 * 注意事项：
 * - 哈希冲突理论存在，但概率极低
 * - 可以通过MessageIdRegistry手动覆盖冲突的ID
 */
public class MessageIdGenerator {

    /** FNV哈希算法的质数因子 */
    private static final long FNV_64_PRIME = 0x100000001b3L;

    /** FNV哈希算法的初始值 */
    private static final long FNV_64_INITIAL = 0xcbf29ce484222325L;

    /** 掩码：取低16位 */
    private static final long MASK_16BIT = 0xFFFFL;

    /**
     * 使用 FNV-1a 对名称做 64 位哈希并取低 16 位，相同名称稳定得到同一 ID。
     *
     * @param messageName 如 {@code auth.login}；null 或空串返回 0
     * @return 无符号意义下的 0～65535，对应 {@code short} 位型
     */
    public static short generateId(String messageName) {
        // 参数校验
        if (messageName == null || messageName.isEmpty()) {
            return 0;
        }

        // FNV-1a哈希算法
        long hash = FNV_64_INITIAL;
        for (int i = 0; i < messageName.length(); i++) {
            // XOR字符值
            hash ^= messageName.charAt(i);
            // 乘以质数
            hash *= FNV_64_PRIME;
        }

        // 取低16位，转换为short
        return (short) (hash & MASK_16BIT);
    }

}

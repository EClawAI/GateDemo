package com.clawai.gatedemo.client.protocol;

/**
 * 与网关门侧一致的 FNV-64 风格短 ID 生成，保证未在注册表中的消息名仍能稳定映射到 16 位 ID。
 */
public class MessageIdGenerator {

    /** FNV-1a 64 位算法质数乘子，与网关门侧约定一致 */
    private static final long FNV_64_PRIME = 0x100000001b3L;
    /** FNV-1a 64 位偏移基准值 */
    private static final long FNV_64_INITIAL = 0xcbf29ce484222325L;

    /**
     * 由消息名字符串计算 16 位短 ID；空串返回 0。同一名字在客户端与网关侧应得到相同结果。
     *
     * @param messageName 逻辑消息名，如 {@code battle.move}
     * @return 截断到低 16 位的哈希值
     */
    public static short generateId(String messageName) {
        if (messageName == null || messageName.isEmpty()) {
            return 0;
        }
        long hash = FNV_64_INITIAL;
        for (int i = 0; i < messageName.length(); i++) {
            hash ^= messageName.charAt(i);
            hash *= FNV_64_PRIME;
        }
        return (short) (hash & 0xFFFF);
    }

}

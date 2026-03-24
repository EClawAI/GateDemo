package com.clawai.gatedemo.client.protocol;

/**
 * 与网关门侧一致的 FNV-64 风格短 ID 生成，保证未在注册表中的消息名仍能稳定映射到 16 位 ID。
 */
public class MessageIdGenerator {

    private static final long FNV_64_PRIME = 0x100000001b3L;
    private static final long FNV_64_INITIAL = 0xcbf29ce484222325L;

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

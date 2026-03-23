package com.clawai.gatedemo.client.protocol;

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

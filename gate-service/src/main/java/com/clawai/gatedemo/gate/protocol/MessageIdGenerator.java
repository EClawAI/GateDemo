package com.clawai.gatedemo.gate.protocol;

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

    public static String generateName(short messageId) {
        return MessageIdRegistry.getNameById(messageId);
    }

    public static void main(String[] args) {
        System.out.println("auth.login -> " + generateId("auth.login"));
        System.out.println("auth.logout -> " + generateId("auth.logout"));
        System.out.println("auth.relogin -> " + generateId("auth.relogin"));
        System.out.println("heartbeat -> " + generateId("heartbeat"));
        System.out.println("battle.move -> " + generateId("battle.move"));
        System.out.println("chat.send -> " + generateId("chat.send"));
    }
}

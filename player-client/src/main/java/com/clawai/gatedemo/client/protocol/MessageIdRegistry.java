package com.clawai.gatedemo.client.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端静态消息名与短 ID 对照表，与 gate-service 默认约定对齐；查表失败时回退到生成器以保证扩展消息可互通。
 */
public class MessageIdRegistry {

    private static final Map<Short, String> ID_TO_NAME = new HashMap<>();
    private static final Map<String, Short> NAME_TO_ID = new HashMap<>();

    static {
        register("auth.login", (short) 0x1001);
        register("auth.logout", (short) 0x1002);
        register("auth.relogin", (short) 0x1003);
        register("heartbeat", (short) 0x2001);
        register("heartbeat_ack", (short) 0x2002);
        register("battle.move", (short) 0x3001);
        register("battle.attack", (short) 0x3002);
        register("chat.send", (short) 0x4001);
        register("chat.receive", (short) 0x4002);
    }

    private static void register(String name, short id) {
        NAME_TO_ID.put(name, id);
        ID_TO_NAME.put(id, name);
    }

    public static short getIdByName(String name) {
        Short id = NAME_TO_ID.get(name);
        return id != null ? id : MessageIdGenerator.generateId(name);
    }

    public static String getNameById(short id) {
        return ID_TO_NAME.get(id);
    }

}

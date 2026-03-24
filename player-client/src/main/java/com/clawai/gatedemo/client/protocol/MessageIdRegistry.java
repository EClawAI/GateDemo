package com.clawai.gatedemo.client.protocol;

import java.util.HashMap;
import java.util.Map;

/**
 * 客户端静态消息名与短 ID 对照表，与 gate-service 默认约定对齐；查表失败时回退到生成器以保证扩展消息可互通。
 */
public class MessageIdRegistry {

    /** 静态注册表，类加载完成后只读，多线程安全读取 */
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

    /**
     * 按消息名解析短 ID；未在表中登记时回退 {@link MessageIdGenerator#generateId(String)}，保证扩展消息可互通。
     *
     * @param name 逻辑消息名
     * @return 对应的 16 位消息 ID
     */
    public static short getIdByName(String name) {
        Short id = NAME_TO_ID.get(name);
        return id != null ? id : MessageIdGenerator.generateId(name);
    }

    /**
     * 由短 ID 反查注册名；仅覆盖静态表内条目，未知 ID 返回 {@code null}。
     *
     * @param id 消息 ID
     * @return 注册时的消息名，未注册则为 null
     */
    public static String getNameById(short id) {
        return ID_TO_NAME.get(id);
    }

}
